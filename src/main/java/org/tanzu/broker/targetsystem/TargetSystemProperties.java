package org.tanzu.broker.targetsystem;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "broker")
public record TargetSystemProperties(
    List<TargetSystemEntry> targetSystems
) {
    public TargetSystemProperties {
        if (targetSystems == null) {
            targetSystems = new ArrayList<>();
        }
    }

    public record TargetSystemEntry(
        String name,
        String type,
        String description,
        String clientId,
        String clientSecret,
        String authorizationServer,
        String mcpServerUrl,
        String discovery,
        String defaultScopes,
        String headerName,
        String headerFormat,
        String apiKey
    ) {}
}
