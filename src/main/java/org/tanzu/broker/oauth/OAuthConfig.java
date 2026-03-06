package org.tanzu.broker.oauth;

public record OAuthConfig(
    String authorizationEndpoint,
    String tokenEndpoint,
    String registrationEndpoint,
    String revocationEndpoint
) {}
