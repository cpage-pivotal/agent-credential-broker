package org.tanzu.broker.token;

import org.tanzu.broker.oauth.OAuthFlowService;
import org.tanzu.broker.targetsystem.TargetSystem;
import org.tanzu.broker.targetsystem.TargetSystemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@EnableScheduling
public class TokenLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TokenLifecycleService.class);

    private final TokenStore tokenStore;
    private final TargetSystemRegistry registry;
    private final OAuthFlowService oAuthFlowService;

    public TokenLifecycleService(TokenStore tokenStore, TargetSystemRegistry registry,
                                  OAuthFlowService oAuthFlowService) {
        this.tokenStore = tokenStore;
        this.registry = registry;
        this.oAuthFlowService = oAuthFlowService;
    }

    @Transactional
    public Optional<StoredToken> getValidToken(String userId, String targetSystem) {
        return tokenStore.get(userId, targetSystem)
            .flatMap(token -> {
                if (token.needsRefresh() && token.refreshToken() != null) {
                    return refreshToken(userId, targetSystem, token);
                }
                if (token.isExpired()) {
                    return Optional.empty();
                }
                return Optional.of(token);
            });
    }

    private Optional<StoredToken> refreshToken(String userId, String targetSystem, StoredToken token) {
        return registry.get(targetSystem)
            .filter(TargetSystem::isOAuth)
            .flatMap(system -> {
                try {
                    var refreshed = oAuthFlowService.refreshAccessToken(system, token.refreshToken());
                    tokenStore.store(userId, targetSystem, refreshed);
                    return Optional.of(refreshed);
                } catch (Exception e) {
                    log.warn("Failed to refresh token for user={} system={}: {}", userId, targetSystem, e.getMessage());
                    return Optional.empty();
                }
            });
    }

    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void cleanupExpiredTokens() {
        log.debug("Running token cleanup");
        tokenStore.removeExpired();
    }
}
