package org.tanzu.broker.oauth;

import org.tanzu.broker.targetsystem.TargetSystem;
import org.tanzu.broker.token.StoredToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OAuthFlowService {

    private static final Logger log = LoggerFactory.getLogger(OAuthFlowService.class);

    private final RestClient restClient = RestClient.create();
    private final Map<String, OAuthState> pendingStates = new ConcurrentHashMap<>();

    public record AuthorizationInitiation(String authorizationUrl, String state) {}

    @SuppressWarnings("unchecked")
    public Optional<OAuthConfig> discoverOAuthConfig(TargetSystem system) {
        if (system.authorizationServer() != null && !system.authorizationServer().isBlank()) {
            return discoverFromWellKnown(system.authorizationServer());
        }
        if ("rfc9728".equals(system.discovery()) && system.mcpServerUrl() != null) {
            return discoverFromMcpServer(system.mcpServerUrl());
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<OAuthConfig> discoverFromWellKnown(String issuer) {
        try {
            var url = issuer.replaceAll("/$", "") + "/.well-known/openid-configuration";
            var response = restClient.get().uri(url).retrieve().body(Map.class);
            if (response != null) {
                return Optional.of(new OAuthConfig(
                    (String) response.get("authorization_endpoint"),
                    (String) response.get("token_endpoint"),
                    (String) response.get("registration_endpoint"),
                    (String) response.get("revocation_endpoint")
                ));
            }
        } catch (Exception e) {
            log.warn("Failed OpenID discovery for {}: {}", issuer, e.getMessage());
        }

        try {
            var url = issuer.replaceAll("/$", "") + "/.well-known/oauth-authorization-server";
            var response = restClient.get().uri(url).retrieve().body(Map.class);
            if (response != null) {
                return Optional.of(new OAuthConfig(
                    (String) response.get("authorization_endpoint"),
                    (String) response.get("token_endpoint"),
                    (String) response.get("registration_endpoint"),
                    (String) response.get("revocation_endpoint")
                ));
            }
        } catch (Exception e) {
            log.warn("Failed OAuth AS discovery for {}: {}", issuer, e.getMessage());
        }

        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<OAuthConfig> discoverFromMcpServer(String mcpServerUrl) {
        try {
            var probeUrl = mcpServerUrl.replaceAll("/mcp$", "");
            restClient.get().uri(probeUrl).retrieve().toBodilessEntity();
        } catch (Exception e) {
            var authHeader = extractWwwAuthenticate(e);
            if (authHeader != null) {
                var resourceMetadataUrl = extractResourceMetadata(authHeader);
                if (resourceMetadataUrl != null) {
                    try {
                        var metadata = restClient.get().uri(resourceMetadataUrl).retrieve().body(Map.class);
                        if (metadata != null && metadata.containsKey("authorization_servers")) {
                            var servers = (java.util.List<String>) metadata.get("authorization_servers");
                            if (!servers.isEmpty()) {
                                return discoverFromWellKnown(servers.getFirst());
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Failed resource metadata fetch from {}: {}", resourceMetadataUrl, ex.getMessage());
                    }
                }
            }
        }
        return Optional.empty();
    }

    public AuthorizationInitiation initiateAuthorization(
        String userId, TargetSystem system, String redirectUri, String scope
    ) {
        var config = discoverOAuthConfig(system)
            .orElseThrow(() -> new IllegalStateException("Could not discover OAuth config for " + system.name()));

        var codeVerifier = PkceGenerator.generateCodeVerifier();
        var codeChallenge = PkceGenerator.generateCodeChallenge(codeVerifier);
        var state = PkceGenerator.generateState();

        pendingStates.put(state, new OAuthState(userId, system.name(), codeVerifier, state, config));

        var effectiveScope = scope != null ? scope : system.defaultScopes();

        var authUrl = config.authorizationEndpoint()
            + "?response_type=code"
            + "&client_id=" + encode(system.clientId())
            + "&redirect_uri=" + encode(redirectUri)
            + "&state=" + encode(state)
            + "&code_challenge=" + encode(codeChallenge)
            + "&code_challenge_method=S256"
            + (effectiveScope != null ? "&scope=" + encode(effectiveScope) : "");

        return new AuthorizationInitiation(authUrl, state);
    }

    @SuppressWarnings("unchecked")
    public StoredToken exchangeCodeForTokens(String state, String code, String redirectUri) {
        var oauthState = pendingStates.remove(state);
        if (oauthState == null) {
            throw new IllegalArgumentException("Unknown state parameter");
        }

        var system = oauthState.targetSystem();
        var config = oauthState.config();

        var body = "grant_type=authorization_code"
            + "&code=" + encode(code)
            + "&redirect_uri=" + encode(redirectUri)
            + "&code_verifier=" + encode(oauthState.codeVerifier())
            + "&client_id=" + encode(getClientId(system))
            + "&client_secret=" + encode(getClientSecret(system));

        var response = restClient.post()
            .uri(config.tokenEndpoint())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(body)
            .retrieve()
            .body(Map.class);

        return parseTokenResponse(response);
    }

    @SuppressWarnings("unchecked")
    public StoredToken refreshAccessToken(TargetSystem system, String refreshToken) {
        var config = discoverOAuthConfig(system)
            .orElseThrow(() -> new IllegalStateException("Could not discover OAuth config for " + system.name()));

        var body = "grant_type=refresh_token"
            + "&refresh_token=" + encode(refreshToken)
            + "&client_id=" + encode(system.clientId())
            + "&client_secret=" + encode(system.clientSecret());

        var response = restClient.post()
            .uri(config.tokenEndpoint())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(body)
            .retrieve()
            .body(Map.class);

        return parseTokenResponse(response);
    }

    public OAuthState getPendingState(String state) {
        return pendingStates.get(state);
    }

    private String getClientId(String targetSystem) {
        // Will be resolved from registry at callback time; stored in OAuthState in future
        return "";
    }

    private String getClientSecret(String targetSystem) {
        return "";
    }

    @SuppressWarnings("unchecked")
    private StoredToken parseTokenResponse(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("Empty token response");
        }

        var accessToken = (String) response.get("access_token");
        var refreshToken = (String) response.get("refresh_token");
        var expiresIn = response.get("expires_in");

        Instant expiresAt = null;
        if (expiresIn instanceof Number n) {
            expiresAt = Instant.now().plusSeconds(n.longValue());
        }

        return new StoredToken(accessToken, refreshToken, expiresAt, "Authorization", "Bearer {token}");
    }

    private String extractWwwAuthenticate(Exception e) {
        var message = e.getMessage();
        if (message != null && message.contains("401")) {
            return message;
        }
        return null;
    }

    private String extractResourceMetadata(String header) {
        var prefix = "resource_metadata=\"";
        int start = header.indexOf(prefix);
        if (start >= 0) {
            start += prefix.length();
            int end = header.indexOf("\"", start);
            if (end > start) {
                return header.substring(start, end);
            }
        }
        return null;
    }

    private static String encode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
