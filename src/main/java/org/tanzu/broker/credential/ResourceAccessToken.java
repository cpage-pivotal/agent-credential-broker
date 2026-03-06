package org.tanzu.broker.credential;

import java.time.Instant;

public record ResourceAccessToken(
    String token,
    Instant expiresAt,
    String headerName,
    String headerValue
) implements CredentialResponse {}
