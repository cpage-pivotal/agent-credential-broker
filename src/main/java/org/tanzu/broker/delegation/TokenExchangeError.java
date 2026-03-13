package org.tanzu.broker.delegation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 8693 / RFC 6749 error response.
 */
public record TokenExchangeError(
    String error,
    @JsonProperty("error_description") String errorDescription
) {

    public static TokenExchangeError invalidRequest(String description) {
        return new TokenExchangeError("invalid_request", description);
    }

    public static TokenExchangeError invalidGrant(String description) {
        return new TokenExchangeError("invalid_grant", description);
    }

    public static TokenExchangeError invalidTarget(String description) {
        return new TokenExchangeError("invalid_target", description);
    }

    public static TokenExchangeError serverError(String description) {
        return new TokenExchangeError("server_error", description);
    }
}
