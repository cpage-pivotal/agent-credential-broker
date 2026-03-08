package org.tanzu.broker.targetsystem;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@EnableConfigurationProperties(TargetSystemProperties.class)
public class TargetSystemRegistry {

    private final Map<String, TargetSystem> systems = new ConcurrentHashMap<>();

    public TargetSystemRegistry(TargetSystemProperties properties) {
        for (var entry : properties.targetSystems()) {
            var type = TargetSystemType.valueOf(entry.type().toUpperCase());
            var system = new TargetSystem(
                entry.name(),
                type,
                entry.description(),
                entry.clientId(),
                entry.clientSecret(),
                entry.authorizationServer(),
                entry.mcpServerUrl(),
                entry.discovery(),
                entry.defaultScopes(),
                entry.headerName(),
                entry.headerFormat(),
                entry.apiKey(),
                entry.requireWorkloadIdentity() != null && entry.requireWorkloadIdentity()
            );
            systems.put(entry.name(), system);
        }
    }

    public Optional<TargetSystem> get(String name) {
        return Optional.ofNullable(systems.get(name));
    }

    public List<TargetSystem> getAll() {
        return List.copyOf(systems.values());
    }

    public void register(TargetSystem system) {
        systems.put(system.name(), system);
    }
}
