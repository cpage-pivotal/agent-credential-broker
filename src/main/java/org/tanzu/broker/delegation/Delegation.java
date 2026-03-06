package org.tanzu.broker.delegation;

import java.time.Instant;
import java.util.List;

public record Delegation(
    String id,
    String userId,
    String agentId,
    List<String> allowedSystems,
    Instant createdAt,
    Instant expiresAt,
    boolean revoked
) {}
