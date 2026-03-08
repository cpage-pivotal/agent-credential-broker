package org.tanzu.broker.token;

import java.io.Serializable;
import java.util.Objects;

public class StoredTokenId implements Serializable {

    private String userId;
    private String targetSystem;

    public StoredTokenId() {}

    public StoredTokenId(String userId, String targetSystem) {
        this.userId = userId;
        this.targetSystem = targetSystem;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StoredTokenId that)) return false;
        return Objects.equals(userId, that.userId)
            && Objects.equals(targetSystem, that.targetSystem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, targetSystem);
    }
}
