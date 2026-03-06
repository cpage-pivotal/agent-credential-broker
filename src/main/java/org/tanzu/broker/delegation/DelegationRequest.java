package org.tanzu.broker.delegation;

import java.util.List;

public record DelegationRequest(
    String agentId,
    List<String> allowedSystems,
    Long ttlHours
) {}
