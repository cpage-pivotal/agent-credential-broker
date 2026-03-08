package org.tanzu.broker.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface StoredTokenRepository extends JpaRepository<StoredTokenEntity, StoredTokenId> {

    @Modifying
    @Query("DELETE FROM StoredTokenEntity t WHERE t.expiresAt IS NOT NULL AND t.expiresAt < :now AND t.refreshToken IS NULL")
    int deleteExpiredTokens(Instant now);
}
