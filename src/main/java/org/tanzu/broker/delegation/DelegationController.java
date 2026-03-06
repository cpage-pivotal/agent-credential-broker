package org.tanzu.broker.delegation;

import org.tanzu.broker.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/delegations")
public class DelegationController {

    private final DelegationTokenService delegationTokenService;

    public DelegationController(DelegationTokenService delegationTokenService) {
        this.delegationTokenService = delegationTokenService;
    }

    @PostMapping
    public ResponseEntity<DelegationResponse> createDelegation(@RequestBody DelegationRequest request) {
        String userId = SecurityUtils.currentUserId();
        Duration ttl = request.ttlHours() != null ? Duration.ofHours(request.ttlHours()) : null;

        var result = delegationTokenService.createDelegation(
            userId, request.agentId(), request.allowedSystems(), ttl
        );

        return ResponseEntity.ok(DelegationResponse.fromDelegation(result.delegation(), result.token()));
    }

    @PostMapping("/inter-app")
    public ResponseEntity<DelegationResponse> createDelegationInterApp(
        @RequestBody DelegationRequest request,
        @RequestHeader("Authorization") String authHeader
    ) {
        // userId is extracted from the validated UAA ID token (JWT sub claim)
        // Spring Security's oauth2ResourceServer validates the token via shared JWKS
        String userId = SecurityUtils.currentUserId();
        Duration ttl = request.ttlHours() != null ? Duration.ofHours(request.ttlHours()) : null;

        var result = delegationTokenService.createDelegation(
            userId, request.agentId(), request.allowedSystems(), ttl
        );

        return ResponseEntity.ok(DelegationResponse.fromDelegation(result.delegation(), result.token()));
    }

    @GetMapping
    public List<DelegationResponse> listDelegations() {
        String userId = SecurityUtils.currentUserId();
        return delegationTokenService.listByUser(userId).stream()
            .map(DelegationResponse::fromDelegation)
            .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revokeDelegation(@PathVariable String id) {
        delegationTokenService.revoke(id, SecurityUtils.currentUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<DelegationResponse> refreshDelegation(@PathVariable String id) {
        var result = delegationTokenService.refresh(id, SecurityUtils.currentUserId());
        return ResponseEntity.ok(DelegationResponse.fromDelegation(result.delegation(), result.token()));
    }
}
