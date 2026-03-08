package org.tanzu.broker.token;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@Transactional
public class TokenStore {

    private final StoredTokenRepository repository;

    public TokenStore(StoredTokenRepository repository) {
        this.repository = repository;
    }

    public void store(String userId, String targetSystem, StoredToken token) {
        var id = new StoredTokenId(userId, targetSystem);
        var entity = repository.findById(id).orElse(null);
        if (entity != null) {
            entity.updateFrom(token);
            repository.save(entity);
        } else {
            repository.save(new StoredTokenEntity(userId, targetSystem, token));
        }
    }

    @Transactional(readOnly = true)
    public Optional<StoredToken> get(String userId, String targetSystem) {
        return repository.findById(new StoredTokenId(userId, targetSystem))
            .map(StoredTokenEntity::toStoredToken);
    }

    public void remove(String userId, String targetSystem) {
        repository.deleteById(new StoredTokenId(userId, targetSystem));
    }

    @Transactional(readOnly = true)
    public boolean hasToken(String userId, String targetSystem) {
        return repository.existsById(new StoredTokenId(userId, targetSystem));
    }

    public void removeExpired() {
        repository.deleteExpiredTokens(Instant.now());
    }
}
