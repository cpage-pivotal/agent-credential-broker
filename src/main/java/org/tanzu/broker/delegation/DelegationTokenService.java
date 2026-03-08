package org.tanzu.broker.delegation;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.tanzu.broker.token.StoredToken;
import org.tanzu.broker.token.TokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DelegationTokenService {

    private final Algorithm signingAlgorithm;
    private final Algorithm previousAlgorithm;
    private final Duration defaultTtl;
    private final Duration maxTtl;
    private final TokenStore tokenStore;
    private final Map<String, Delegation> delegations = new ConcurrentHashMap<>();
    private final Set<String> revokedJtis = ConcurrentHashMap.newKeySet();

    public DelegationTokenService(
        @Value("${broker.delegation.signing-secret}") String signingSecret,
        @Value("${broker.delegation.previous-signing-secret:}") String previousSigningSecret,
        @Value("${broker.delegation.default-ttl:72h}") Duration defaultTtl,
        @Value("${broker.delegation.max-ttl:720h}") Duration maxTtl,
        TokenStore tokenStore
    ) {
        this.signingAlgorithm = Algorithm.HMAC256(signingSecret);
        this.previousAlgorithm = previousSigningSecret != null && !previousSigningSecret.isBlank()
            ? Algorithm.HMAC256(previousSigningSecret) : null;
        this.defaultTtl = defaultTtl;
        this.maxTtl = maxTtl;
        this.tokenStore = tokenStore;
    }

    public record DelegationTokenResult(Delegation delegation, String token) {}

    public DelegationTokenResult createDelegation(
        String userId, String agentId, List<String> allowedSystems, Duration ttl
    ) {
        Duration effectiveTtl = ttl != null ? ttl : defaultTtl;
        if (effectiveTtl.compareTo(maxTtl) > 0) {
            effectiveTtl = maxTtl;
        }

        var now = Instant.now();

        Instant requestedExpiry = now.plus(effectiveTtl);
        Instant cappedExpiry = allowedSystems.stream()
            .flatMap(s -> tokenStore.get(userId, s).stream())
            .filter(t -> t.refreshToken() == null)
            .map(StoredToken::expiresAt)
            .filter(Objects::nonNull)
            .filter(exp -> exp.isBefore(requestedExpiry))
            .min(Comparator.naturalOrder())
            .orElse(requestedExpiry);
        effectiveTtl = Duration.between(now, cappedExpiry);
        var jti = "deleg-" + UUID.randomUUID().toString().substring(0, 8);

        var delegation = new Delegation(
            jti, userId, agentId, List.copyOf(allowedSystems),
            now, now.plus(effectiveTtl), false
        );

        String token = JWT.create()
            .withSubject(userId)
            .withClaim("agent", agentId)
            .withClaim("systems", new ArrayList<>(allowedSystems))
            .withIssuedAt(now)
            .withExpiresAt(now.plus(effectiveTtl))
            .withJWTId(jti)
            .sign(signingAlgorithm);

        delegations.put(jti, delegation);
        return new DelegationTokenResult(delegation, token);
    }

    public DecodedJWT validateDelegationToken(String token) {
        try {
            return JWT.require(signingAlgorithm)
                .build()
                .verify(token);
        } catch (JWTVerificationException e) {
            if (previousAlgorithm != null) {
                try {
                    return JWT.require(previousAlgorithm)
                        .build()
                        .verify(token);
                } catch (JWTVerificationException ignored) {
                    // fall through
                }
            }
            throw e;
        }
    }

    public boolean isRevoked(String jti) {
        return revokedJtis.contains(jti);
    }

    public void revoke(String delegationId, String userId) {
        var delegation = delegations.get(delegationId);
        if (delegation != null && delegation.userId().equals(userId)) {
            revokedJtis.add(delegationId);
            delegations.put(delegationId, new Delegation(
                delegation.id(), delegation.userId(), delegation.agentId(),
                delegation.allowedSystems(), delegation.createdAt(), delegation.expiresAt(), true
            ));
        }
    }

    public DelegationTokenResult refresh(String delegationId, String userId) {
        var delegation = delegations.get(delegationId);
        if (delegation == null || !delegation.userId().equals(userId) || delegation.revoked()) {
            throw new IllegalArgumentException("Delegation not found or revoked");
        }
        revokedJtis.add(delegationId);
        return createDelegation(userId, delegation.agentId(), delegation.allowedSystems(), null);
    }

    public List<Delegation> listByUser(String userId) {
        return delegations.values().stream()
            .filter(d -> d.userId().equals(userId))
            .sorted(Comparator.comparing(Delegation::createdAt).reversed())
            .toList();
    }

    public void cleanupExpired() {
        var now = Instant.now();
        delegations.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        // Revoked JTIs are cleaned when their delegation expires
    }
}
