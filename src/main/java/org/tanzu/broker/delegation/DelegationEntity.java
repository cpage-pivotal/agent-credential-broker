package org.tanzu.broker.delegation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;

@Entity
@Table(name = "delegations")
public class DelegationEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "allowed_systems", nullable = false, columnDefinition = "TEXT")
    private String allowedSystems;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    protected DelegationEntity() {}

    public DelegationEntity(Delegation delegation) {
        this.id = delegation.id();
        this.userId = delegation.userId();
        this.agentId = delegation.agentId();
        this.allowedSystems = String.join(",", delegation.allowedSystems());
        this.createdAt = delegation.createdAt();
        this.expiresAt = delegation.expiresAt();
        this.revoked = delegation.revoked();
    }

    public Delegation toDelegation() {
        return new Delegation(
            id, userId, agentId,
            Arrays.asList(allowedSystems.split(",")),
            createdAt, expiresAt, revoked
        );
    }

    public void markRevoked() {
        this.revoked = true;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
}
