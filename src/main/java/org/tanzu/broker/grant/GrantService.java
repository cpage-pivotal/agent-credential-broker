package org.tanzu.broker.grant;

import org.tanzu.broker.oauth.OAuthFlowService;
import org.tanzu.broker.targetsystem.TargetSystem;
import org.tanzu.broker.targetsystem.TargetSystemRegistry;
import org.tanzu.broker.token.StoredToken;
import org.tanzu.broker.token.TokenStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GrantService {

    private final TargetSystemRegistry registry;
    private final TokenStore tokenStore;
    private final OAuthFlowService oAuthFlowService;

    public GrantService(TargetSystemRegistry registry, TokenStore tokenStore,
                        OAuthFlowService oAuthFlowService) {
        this.registry = registry;
        this.tokenStore = tokenStore;
        this.oAuthFlowService = oAuthFlowService;
    }

    public List<UserGrant> listGrants(String userId) {
        return registry.getAll().stream()
            .map(system -> toGrant(userId, system))
            .toList();
    }

    public OAuthFlowService.AuthorizationInitiation initiateOAuthGrant(
        String userId, String targetSystem, String redirectUri, String scope
    ) {
        var system = registry.get(targetSystem)
            .orElseThrow(() -> new IllegalArgumentException("Unknown target system: " + targetSystem));

        if (!system.isOAuth()) {
            throw new IllegalArgumentException("Target system " + targetSystem + " does not use OAuth");
        }

        return oAuthFlowService.initiateAuthorization(userId, system, redirectUri, scope);
    }

    public void storeUserProvidedToken(String userId, String targetSystem, String token) {
        var system = registry.get(targetSystem)
            .orElseThrow(() -> new IllegalArgumentException("Unknown target system: " + targetSystem));

        if (!system.isUserProvided()) {
            throw new IllegalArgumentException("Target system " + targetSystem + " does not accept user-provided tokens");
        }

        var stored = new StoredToken(token, null, null, system.resolvedHeaderName(), system.headerFormat());
        tokenStore.store(userId, targetSystem, stored);
    }

    public void revokeGrant(String userId, String targetSystem) {
        tokenStore.remove(userId, targetSystem);
    }

    public boolean hasGrant(String userId, String targetSystem) {
        var system = registry.get(targetSystem);
        if (system.isEmpty()) return false;

        if (system.get().isStaticApiKey()) return true;

        return tokenStore.hasToken(userId, targetSystem);
    }

    private UserGrant toGrant(String userId, TargetSystem system) {
        if (system.isStaticApiKey()) {
            return new UserGrant(
                system.name(), system.type(), system.description(),
                UserGrant.GrantStatus.CONNECTED, null, null, false
            );
        }

        var tokenOpt = tokenStore.get(userId, system.name());
        if (tokenOpt.isEmpty()) {
            return new UserGrant(
                system.name(), system.type(), system.description(),
                UserGrant.GrantStatus.NOT_CONNECTED, null, null, false
            );
        }

        var token = tokenOpt.get();
        if (token.isExpired() && token.refreshToken() == null) {
            return new UserGrant(
                system.name(), system.type(), system.description(),
                UserGrant.GrantStatus.EXPIRED, null, null, false
            );
        }

        return new UserGrant(
            system.name(), system.type(), system.description(),
            UserGrant.GrantStatus.CONNECTED, null, token.expiresAt(), token.refreshToken() != null
        );
    }
}
