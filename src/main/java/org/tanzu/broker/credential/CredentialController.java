package org.tanzu.broker.credential;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.tanzu.broker.delegation.DelegationTokenService;
import org.tanzu.broker.grant.GrantService;
import org.tanzu.broker.security.WorkloadIdentityValidator;
import org.tanzu.broker.targetsystem.TargetSystemRegistry;
import org.tanzu.broker.token.TokenLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private static final Logger log = LoggerFactory.getLogger(CredentialController.class);

    private final DelegationTokenService delegationTokenService;
    private final GrantService grantService;
    private final TargetSystemRegistry registry;
    private final TokenLifecycleService tokenLifecycleService;
    private final WorkloadIdentityValidator workloadIdentityValidator;

    public CredentialController(DelegationTokenService delegationTokenService,
                                 GrantService grantService,
                                 TargetSystemRegistry registry,
                                 TokenLifecycleService tokenLifecycleService,
                                 WorkloadIdentityValidator workloadIdentityValidator) {
        this.delegationTokenService = delegationTokenService;
        this.grantService = grantService;
        this.registry = registry;
        this.tokenLifecycleService = tokenLifecycleService;
        this.workloadIdentityValidator = workloadIdentityValidator;
    }

    @PostMapping("/request")
    public ResponseEntity<?> requestCredential(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @RequestHeader(value = "X-Forwarded-Client-Cert", required = false) String xfccHeader,
        @RequestBody CredentialRequest request
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Missing delegation token"));
        }

        String token = authHeader.substring(7);
        DecodedJWT jwt;
        try {
            jwt = delegationTokenService.validateDelegationToken(token);
        } catch (JWTVerificationException e) {
            log.warn("Invalid delegation token: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid delegation token"));
        }

        String jti = jwt.getId();
        if (delegationTokenService.isRevoked(jti)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Delegation token has been revoked"));
        }

        var allowedSystems = jwt.getClaim("systems").asList(String.class);
        if (allowedSystems == null || !allowedSystems.contains(request.targetSystem())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Target system not in delegation scope"));
        }

        String userId = jwt.getSubject();

        var system = registry.get(request.targetSystem());
        if (system.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Unknown target system: " + request.targetSystem()));
        }

        var ts = system.get();

        if (ts.requireWorkloadIdentity() && !workloadIdentityValidator.isValidPlatformWorkload(xfccHeader)) {
            log.warn("Workload identity required for {} but XFCC validation failed (agent: {})",
                request.targetSystem(), jwt.getClaim("agent").asString());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Workload identity required — credential requests for "
                    + request.targetSystem()
                    + " must originate from a platform workload with a valid instance identity certificate"));
        }

        if (ts.isStaticApiKey()) {
            var response = new ResourceAccessToken(
                ts.apiKey(),
                null,
                ts.resolvedHeaderName(),
                ts.formatHeaderValue(ts.apiKey())
            );
            return ResponseEntity.ok(response);
        }

        if (!grantService.hasGrant(userId, request.targetSystem())) {
            var response = new UserDelegationRequired(
                request.targetSystem(),
                "/grants"
            );
            return ResponseEntity.ok(response);
        }

        var storedToken = tokenLifecycleService.getValidToken(userId, request.targetSystem());
        if (storedToken.isEmpty()) {
            var response = new UserDelegationRequired(
                request.targetSystem(),
                "/grants"
            );
            return ResponseEntity.ok(response);
        }

        var st = storedToken.get();
        var response = new ResourceAccessToken(
            st.accessToken(),
            st.expiresAt(),
            st.headerName() != null ? st.headerName() : ts.resolvedHeaderName(),
            ts.formatHeaderValue(st.accessToken())
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<?> credentialStatus(
        @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Missing delegation token"));
        }

        String token = authHeader.substring(7);
        DecodedJWT jwt;
        try {
            jwt = delegationTokenService.validateDelegationToken(token);
        } catch (JWTVerificationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid delegation token"));
        }

        String userId = jwt.getSubject();
        var allowedSystems = jwt.getClaim("systems").asList(String.class);
        Map<String, String> statuses = new LinkedHashMap<>();

        if (allowedSystems != null) {
            for (String sys : allowedSystems) {
                statuses.put(sys, grantService.hasGrant(userId, sys) ? "connected" : "not_connected");
            }
        }

        return ResponseEntity.ok(statuses);
    }
}
