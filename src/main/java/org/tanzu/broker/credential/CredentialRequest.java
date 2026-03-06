package org.tanzu.broker.credential;

public record CredentialRequest(
    String targetSystem,
    String scope
) {}
