package org.tanzu.broker.oauth;

public record OAuthState(
    String userId,
    String targetSystem,
    String codeVerifier,
    String state,
    OAuthConfig config
) {}
