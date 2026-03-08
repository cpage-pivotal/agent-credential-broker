package org.tanzu.broker.delegation;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "revoked_jtis")
public class RevokedJtiEntity {

    @Id
    private String jti;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RevokedJtiEntity() {}

    public RevokedJtiEntity(String jti, Instant expiresAt) {
        this.jti = jti;
        this.revokedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public String getJti() { return jti; }
    public Instant getExpiresAt() { return expiresAt; }
}
