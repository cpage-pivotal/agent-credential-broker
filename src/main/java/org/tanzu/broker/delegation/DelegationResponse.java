package org.tanzu.broker.delegation;

import java.time.Instant;
import java.util.List;

public record DelegationResponse(
    String id,
    String agentId,
    List<String> allowedSystems,
    Instant createdAt,
    Instant expiresAt,
    boolean revoked,
    String token
) {
    public static DelegationResponse fromDelegation(Delegation delegation, String token) {
        return new DelegationResponse(
            delegation.id(),
            delegation.agentId(),
            delegation.allowedSystems(),
            delegation.createdAt(),
            delegation.expiresAt(),
            delegation.revoked(),
            token
        );
    }

    public static DelegationResponse fromDelegation(Delegation delegation) {
        return fromDelegation(delegation, null);
    }
}
