package org.tanzu.broker.delegation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface DelegationRepository extends JpaRepository<DelegationEntity, String> {

    List<DelegationEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying
    @Query("DELETE FROM DelegationEntity d WHERE d.expiresAt < :now")
    int deleteExpired(Instant now);
}
