package org.tanzu.broker.delegation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface RevokedJtiRepository extends JpaRepository<RevokedJtiEntity, String> {

    @Modifying
    @Query("DELETE FROM RevokedJtiEntity r WHERE r.expiresAt < :now")
    int deleteExpired(Instant now);
}
