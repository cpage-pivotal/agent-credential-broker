package org.tanzu.broker.token;

import java.time.Instant;

public record StoredToken(
    String accessToken,
    String refreshToken,
    Instant expiresAt,
    String headerName,
    String headerFormat
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean needsRefresh() {
        return expiresAt != null && Instant.now().isAfter(expiresAt.minusSeconds(300));
    }
}
