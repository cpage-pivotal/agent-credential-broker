package org.tanzu.broker.grant;

import org.tanzu.broker.targetsystem.TargetSystemType;

import java.time.Instant;

public record UserGrant(
    String targetSystem,
    TargetSystemType type,
    String description,
    GrantStatus status,
    Instant grantedAt
) {
    public enum GrantStatus {
        CONNECTED, NOT_CONNECTED, EXPIRED
    }
}
