package org.tanzu.broker.token;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenStore {

    private final Map<String, StoredToken> tokens = new ConcurrentHashMap<>();

    private String key(String userId, String targetSystem) {
        return userId + ":" + targetSystem;
    }

    public void store(String userId, String targetSystem, StoredToken token) {
        tokens.put(key(userId, targetSystem), token);
    }

    public Optional<StoredToken> get(String userId, String targetSystem) {
        return Optional.ofNullable(tokens.get(key(userId, targetSystem)));
    }

    public void remove(String userId, String targetSystem) {
        tokens.remove(key(userId, targetSystem));
    }

    public boolean hasToken(String userId, String targetSystem) {
        return tokens.containsKey(key(userId, targetSystem));
    }
}
