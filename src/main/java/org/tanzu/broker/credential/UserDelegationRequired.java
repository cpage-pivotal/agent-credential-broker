package org.tanzu.broker.credential;

public record UserDelegationRequired(
    String targetSystem,
    String brokerAuthorizationUrl
) implements CredentialResponse {}
