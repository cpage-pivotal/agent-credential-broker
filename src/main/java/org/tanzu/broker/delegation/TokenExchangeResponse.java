package org.tanzu.broker.delegation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 8693 Token Exchange successful response.
 */
public record TokenExchangeResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("issued_token_type") String issuedTokenType,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("expires_in") long expiresIn
) {

    public static TokenExchangeResponse of(String accessToken, long expiresInSeconds) {
        return new TokenExchangeResponse(
                accessToken,
                TokenExchangeRequest.TOKEN_TYPE_JWT,
                "Bearer",
                expiresInSeconds
        );
    }
}
