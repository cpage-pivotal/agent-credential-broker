package org.tanzu.broker.token;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "stored_tokens")
@IdClass(StoredTokenId.class)
public class StoredTokenEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Id
    @Column(name = "target_system")
    private String targetSystem;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "header_name")
    private String headerName;

    @Column(name = "header_format")
    private String headerFormat;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoredTokenEntity() {}

    public StoredTokenEntity(String userId, String targetSystem, StoredToken token) {
        this.userId = userId;
        this.targetSystem = targetSystem;
        this.accessToken = token.accessToken();
        this.refreshToken = token.refreshToken();
        this.expiresAt = token.expiresAt();
        this.headerName = token.headerName();
        this.headerFormat = token.headerFormat();
        this.updatedAt = Instant.now();
    }

    public StoredToken toStoredToken() {
        return new StoredToken(accessToken, refreshToken, expiresAt, headerName, headerFormat);
    }

    public void updateFrom(StoredToken token) {
        this.accessToken = token.accessToken();
        this.refreshToken = token.refreshToken();
        this.expiresAt = token.expiresAt();
        this.headerName = token.headerName();
        this.headerFormat = token.headerFormat();
        this.updatedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public String getTargetSystem() { return targetSystem; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getRefreshToken() { return refreshToken; }
}
