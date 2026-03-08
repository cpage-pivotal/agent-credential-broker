package org.tanzu.broker.delegation;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.tanzu.broker.token.StoredToken;
import org.tanzu.broker.token.TokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class DelegationTokenService {

    private final Algorithm signingAlgorithm;
    private final Algorithm previousAlgorithm;
    private final Duration defaultTtl;
    private final Duration maxTtl;
    private final TokenStore tokenStore;
    private final DelegationRepository delegationRepository;
    private final RevokedJtiRepository revokedJtiRepository;

    public DelegationTokenService(
        @Value("${broker.delegation.signing-secret}") String signingSecret,
        @Value("${broker.delegation.previous-signing-secret:}") String previousSigningSecret,
        @Value("${broker.delegation.default-ttl:72h}") Duration defaultTtl,
        @Value("${broker.delegation.max-ttl:720h}") Duration maxTtl,
        TokenStore tokenStore,
        DelegationRepository delegationRepository,
        RevokedJtiRepository revokedJtiRepository
    ) {
        this.signingAlgorithm = Algorithm.HMAC256(signingSecret);
        this.previousAlgorithm = previousSigningSecret != null && !previousSigningSecret.isBlank()
            ? Algorithm.HMAC256(previousSigningSecret) : null;
        this.defaultTtl = defaultTtl;
        this.maxTtl = maxTtl;
        this.tokenStore = tokenStore;
        this.delegationRepository = delegationRepository;
        this.revokedJtiRepository = revokedJtiRepository;
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

        delegationRepository.save(new DelegationEntity(delegation));
        return new DelegationTokenResult(delegation, token);
    }

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    public boolean isRevoked(String jti) {
        return revokedJtiRepository.existsById(jti);
    }

    public void revoke(String delegationId, String userId) {
        delegationRepository.findById(delegationId)
            .filter(entity -> entity.getUserId().equals(userId))
            .ifPresent(entity -> {
                entity.markRevoked();
                delegationRepository.save(entity);
                revokedJtiRepository.save(new RevokedJtiEntity(delegationId, entity.getExpiresAt()));
            });
    }

    public DelegationTokenResult refresh(String delegationId, String userId) {
        var entity = delegationRepository.findById(delegationId)
            .filter(e -> e.getUserId().equals(userId) && !e.isRevoked())
            .orElseThrow(() -> new IllegalArgumentException("Delegation not found or revoked"));

        var delegation = entity.toDelegation();
        revokedJtiRepository.save(new RevokedJtiEntity(delegationId, entity.getExpiresAt()));
        entity.markRevoked();
        delegationRepository.save(entity);

        return createDelegation(userId, delegation.agentId(), delegation.allowedSystems(), null);
    }

    @Transactional(readOnly = true)
    public List<Delegation> listByUser(String userId) {
        return delegationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(DelegationEntity::toDelegation)
            .toList();
    }

    public void cleanupExpired() {
        var now = Instant.now();
        delegationRepository.deleteExpired(now);
        revokedJtiRepository.deleteExpired(now);
    }
}
