package org.tanzu.broker.grant;

import org.tanzu.broker.targetsystem.TargetSystemType;

import java.time.Instant;

public record UserGrant(
    String targetSystem,
    TargetSystemType type,
    String description,
    GrantStatus status,
    Instant grantedAt,
    Instant expiresAt,
    boolean hasRefreshToken,
    boolean requireWorkloadIdentity
) {
    public enum GrantStatus {
        CONNECTED, NOT_CONNECTED, EXPIRED
    }
}
