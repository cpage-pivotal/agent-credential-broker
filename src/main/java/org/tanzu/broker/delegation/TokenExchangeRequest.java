package org.tanzu.broker.delegation;

/**
 * RFC 8693 Token Exchange request parameters (form-encoded).
 *
 * @param grantType          must be {@code urn:ietf:params:oauth:grant-type:token-exchange}
 * @param subjectToken       the user's identity token (JWT)
 * @param subjectTokenType   must be {@code urn:ietf:params:oauth:token-type:jwt}
 * @param scope              space-delimited list of target systems
 * @param audience           intended audience for the issued token (optional)
 * @param requestedTokenType desired output token type (optional, always JWT)
 */
public record TokenExchangeRequest(
    String grantType,
    String subjectToken,
    String subjectTokenType,
    String scope,
    String audience,
    String requestedTokenType
) {

    public static final String GRANT_TYPE_TOKEN_EXCHANGE =
            "urn:ietf:params:oauth:grant-type:token-exchange";

    public static final String TOKEN_TYPE_JWT =
            "urn:ietf:params:oauth:token-type:jwt";
}
