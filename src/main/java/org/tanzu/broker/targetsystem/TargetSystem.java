package org.tanzu.broker.targetsystem;

public record TargetSystem(
    String name,
    TargetSystemType type,
    String description,
    String clientId,
    String clientSecret,
    String authorizationServer,
    String mcpServerUrl,
    String discovery,
    String defaultScopes,
    String headerName,
    String headerFormat,
    String apiKey,
    boolean requireWorkloadIdentity
) {

    public boolean isOAuth() {
        return type == TargetSystemType.OAUTH_AUTHORIZATION_CODE
            || type == TargetSystemType.OAUTH_CLIENT_CREDENTIALS;
    }

    public boolean isUserProvided() {
        return type == TargetSystemType.USER_PROVIDED_TOKEN;
    }

    public boolean isStaticApiKey() {
        return type == TargetSystemType.STATIC_API_KEY;
    }

    public String resolvedHeaderName() {
        if (headerName != null && !headerName.isBlank()) {
            return headerName;
        }
        return "Authorization";
    }

    public String formatHeaderValue(String token) {
        if (headerFormat != null && !headerFormat.isBlank()) {
            return headerFormat.replace("{token}", token);
        }
        return "Bearer " + token;
    }
}
