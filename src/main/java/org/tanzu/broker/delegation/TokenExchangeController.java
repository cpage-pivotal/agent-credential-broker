package org.tanzu.broker.delegation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * RFC 8693 OAuth 2.0 Token Exchange endpoint.
 * <p>
 * Agents present a user JWT (subject_token) and authenticate via mTLS (workload identity).
 * The endpoint issues a delegation JWT binding the user and workload identities.
 */
@RestController
@RequestMapping("/api/delegations")
public class TokenExchangeController {

    private static final Logger log = LoggerFactory.getLogger(TokenExchangeController.class);

    private final DelegationTokenService delegationTokenService;
    private final SubjectTokenValidator subjectTokenValidator;

    public TokenExchangeController(DelegationTokenService delegationTokenService,
                                   SubjectTokenValidator subjectTokenValidator) {
        this.delegationTokenService = delegationTokenService;
        this.subjectTokenValidator = subjectTokenValidator;
    }

    @PostMapping(value = "/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tokenExchange(
            @RequestParam("grant_type") String grantType,
            @RequestParam("subject_token") String subjectToken,
            @RequestParam("subject_token_type") String subjectTokenType,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "audience", required = false) String audience,
            @RequestParam(value = "requested_token_type", required = false) String requestedTokenType,
            Authentication authentication
    ) {
        if (!TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE.equals(grantType)) {
            return badRequest(TokenExchangeError.invalidRequest(
                    "grant_type must be " + TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE));
        }

        if (!TokenExchangeRequest.TOKEN_TYPE_JWT.equals(subjectTokenType)) {
            return badRequest(TokenExchangeError.invalidRequest(
                    "subject_token_type must be " + TokenExchangeRequest.TOKEN_TYPE_JWT));
        }

        if (subjectToken == null || subjectToken.isBlank()) {
            return badRequest(TokenExchangeError.invalidRequest("subject_token is required"));
        }

        var validationResult = subjectTokenValidator.validate(subjectToken);
        if (!validationResult.isValid()) {
            log.warn("Subject token validation failed: {}", validationResult.error());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(TokenExchangeError.invalidGrant(validationResult.error()));
        }

        String userId = validationResult.getUserId().orElseThrow();

        String agentId = authentication.getName();
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(TokenExchangeError.invalidGrant(
                            "Could not extract workload identity from client certificate"));
        }

        List<String> allowedSystems = parseScope(scope);
        if (allowedSystems.isEmpty()) {
            return badRequest(TokenExchangeError.invalidRequest(
                    "scope is required (space-delimited list of target systems)"));
        }

        log.info("Token exchange: user={}, workload={}, systems={}", userId, agentId, allowedSystems);

        var result = delegationTokenService.createDelegation(userId, agentId, allowedSystems, null);

        long expiresIn = Duration.between(Instant.now(), result.delegation().expiresAt()).getSeconds();

        return ResponseEntity.ok(TokenExchangeResponse.of(result.token(), expiresIn));
    }

    private List<String> parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scope.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private ResponseEntity<TokenExchangeError> badRequest(TokenExchangeError error) {
        return ResponseEntity.badRequest().body(error);
    }
}
