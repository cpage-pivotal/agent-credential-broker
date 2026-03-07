package org.tanzu.broker.oauth;

import org.tanzu.broker.targetsystem.TargetSystem;
import org.tanzu.broker.targetsystem.TargetSystemRegistry;
import org.tanzu.broker.token.StoredToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OAuthFlowService {

    private static final Logger log = LoggerFactory.getLogger(OAuthFlowService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern AUTH_PARAM_QUOTED = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern AUTH_PARAM_UNQUOTED = Pattern.compile("(\\w+)\\s*=\\s*([^\\s,\"]+)");

    private final HttpClient httpClient;
    private final TargetSystemRegistry registry;
    private final Map<String, OAuthState> pendingStates = new ConcurrentHashMap<>();
    private final Map<String, OAuthConfig> configCache = new ConcurrentHashMap<>();

    public OAuthFlowService(TargetSystemRegistry registry) {
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public record AuthorizationInitiation(String authorizationUrl, String state) {}

    public Optional<OAuthConfig> discoverOAuthConfig(TargetSystem system) {
        var cached = configCache.get(system.name());
        if (cached != null) return Optional.of(cached);

        Optional<OAuthConfig> result;

        if (system.authorizationServer() != null && !system.authorizationServer().isBlank()) {
            result = discoverFromWellKnown(system.authorizationServer());
        } else if ("rfc9728".equals(system.discovery()) && system.mcpServerUrl() != null) {
            result = discoverFromMcpServer(system.mcpServerUrl());
        } else {
            result = Optional.empty();
        }

        result.ifPresent(config -> configCache.put(system.name(), config));
        return result;
    }

    /**
     * RFC 9728 discovery: probe the MCP server URL for a 401, parse the
     * WWW-Authenticate header for resource_metadata, fetch Protected Resource
     * Metadata, then fetch Authorization Server Metadata.
     */
    private Optional<OAuthConfig> discoverFromMcpServer(String mcpServerUrl) {
        try {
            log.info("Probing MCP server for OAuth discovery: {}", mcpServerUrl);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(mcpServerUrl))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 401) {
                log.warn("MCP server returned {} (expected 401) from {}", response.statusCode(), mcpServerUrl);
                return Optional.empty();
            }

            var authHeader = response.headers().firstValue("WWW-Authenticate").orElse(null);
            String resourceMetadataUrl = null;

            if (authHeader != null) {
                resourceMetadataUrl = extractAuthParam(authHeader, "resource_metadata");
                log.debug("Parsed WWW-Authenticate: resource_metadata={}", resourceMetadataUrl);
            }

            if (resourceMetadataUrl == null) {
                resourceMetadataUrl = buildWellKnownResourceMetadataUrl(mcpServerUrl);
                log.debug("Falling back to well-known resource metadata URL: {}", resourceMetadataUrl);
            }

            var resourceMetadata = fetchJson(resourceMetadataUrl);
            if (resourceMetadata == null) {
                log.warn("Failed to fetch Protected Resource Metadata from {}", resourceMetadataUrl);
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            var authServers = (List<String>) resourceMetadata.get("authorization_servers");
            if (authServers == null || authServers.isEmpty()) {
                log.warn("No authorization_servers in Protected Resource Metadata");
                return Optional.empty();
            }

            String authServerUrl = authServers.getFirst();
            log.info("Discovered authorization server: {}", authServerUrl);

            return discoverFromWellKnown(authServerUrl);

        } catch (Exception e) {
            log.warn("RFC 9728 discovery failed for {}: {}", mcpServerUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<OAuthConfig> discoverFromWellKnown(String issuer) {
        URI uri = URI.create(issuer);
        String baseUrl = uri.getScheme() + "://" + uri.getHost()
            + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String path = uri.getPath();

        List<String> discoveryUrls = new ArrayList<>();

        if (path != null && !path.isEmpty() && !path.equals("/")) {
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;
            discoveryUrls.add(baseUrl + "/.well-known/oauth-authorization-server/" + cleanPath);
            discoveryUrls.add(baseUrl + "/.well-known/openid-configuration/" + cleanPath);
            discoveryUrls.add(issuer + "/.well-known/openid-configuration");
        } else {
            discoveryUrls.add(baseUrl + "/.well-known/oauth-authorization-server");
            discoveryUrls.add(baseUrl + "/.well-known/openid-configuration");
        }

        for (String url : discoveryUrls) {
            try {
                log.debug("Trying AS metadata at: {}", url);
                var metadata = fetchJson(url);
                if (metadata != null) {
                    var config = new OAuthConfig(
                        (String) metadata.get("authorization_endpoint"),
                        (String) metadata.get("token_endpoint"),
                        (String) metadata.get("registration_endpoint"),
                        (String) metadata.get("revocation_endpoint")
                    );
                    if (config.authorizationEndpoint() != null && config.tokenEndpoint() != null) {
                        log.info("Discovered OAuth config: authz={}, token={}", config.authorizationEndpoint(), config.tokenEndpoint());
                        return Optional.of(config);
                    }
                }
            } catch (Exception e) {
                log.debug("Discovery attempt failed for {}: {}", url, e.getMessage());
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
            + "&resource=" + encode(system.mcpServerUrl())
            + (effectiveScope != null ? "&scope=" + encode(effectiveScope) : "");

        return new AuthorizationInitiation(authUrl, state);
    }

    public StoredToken exchangeCodeForTokens(String state, String code, String redirectUri) {
        var oauthState = pendingStates.remove(state);
        if (oauthState == null) {
            throw new IllegalArgumentException("Unknown or expired state parameter");
        }

        var system = registry.get(oauthState.targetSystem())
            .orElseThrow(() -> new IllegalStateException("Unknown target system: " + oauthState.targetSystem()));
        var config = oauthState.config();

        var body = "grant_type=authorization_code"
            + "&code=" + encode(code)
            + "&redirect_uri=" + encode(redirectUri)
            + "&code_verifier=" + encode(oauthState.codeVerifier())
            + "&client_id=" + encode(system.clientId())
            + "&resource=" + encode(system.mcpServerUrl());

        if (system.clientSecret() != null && !system.clientSecret().isBlank()) {
            body += "&client_secret=" + encode(system.clientSecret());
        }

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(config.tokenEndpoint()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Token exchange failed: {} - {}", response.statusCode(), response.body());
                throw new IllegalStateException("Token exchange failed: HTTP " + response.statusCode());
            }

            return parseTokenResponse(response.body());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Token exchange failed", e);
        }
    }

    public StoredToken refreshAccessToken(TargetSystem system, String refreshToken) {
        var config = discoverOAuthConfig(system)
            .orElseThrow(() -> new IllegalStateException("Could not discover OAuth config for " + system.name()));

        var body = "grant_type=refresh_token"
            + "&refresh_token=" + encode(refreshToken)
            + "&client_id=" + encode(system.clientId());

        if (system.clientSecret() != null && !system.clientSecret().isBlank()) {
            body += "&client_secret=" + encode(system.clientSecret());
        }

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(config.tokenEndpoint()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Token refresh failed: HTTP " + response.statusCode());
            }

            return parseTokenResponse(response.body());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Token refresh failed", e);
        }
    }

    public OAuthState getPendingState(String state) {
        return pendingStates.get(state);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJson(String url) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.readValue(response.body(), Map.class);
            }
        } catch (Exception e) {
            log.debug("Failed to fetch JSON from {}: {}", url, e.getMessage());
        }
        return null;
    }

    private StoredToken parseTokenResponse(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            var response = mapper.readValue(json, Map.class);

            var accessToken = (String) response.get("access_token");
            var refreshToken = (String) response.get("refresh_token");
            var expiresIn = response.get("expires_in");

            Instant expiresAt = null;
            if (expiresIn instanceof Number n) {
                expiresAt = Instant.now().plusSeconds(n.longValue());
            }

            return new StoredToken(accessToken, refreshToken, expiresAt, "Authorization", "Bearer {token}");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse token response", e);
        }
    }

    private String extractAuthParam(String header, String paramName) {
        Matcher quoted = AUTH_PARAM_QUOTED.matcher(header);
        while (quoted.find()) {
            if (paramName.equalsIgnoreCase(quoted.group(1))) {
                return quoted.group(2);
            }
        }
        Matcher unquoted = AUTH_PARAM_UNQUOTED.matcher(header);
        while (unquoted.find()) {
            if (paramName.equalsIgnoreCase(unquoted.group(1))) {
                return unquoted.group(2);
            }
        }
        return null;
    }

    private String buildWellKnownResourceMetadataUrl(String mcpServerUrl) {
        URI uri = URI.create(mcpServerUrl);
        String path = uri.getPath();
        String baseUrl = uri.getScheme() + "://" + uri.getHost()
            + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        if (path != null && !path.isEmpty() && !path.equals("/")) {
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;
            return baseUrl + "/.well-known/oauth-protected-resource/" + cleanPath;
        }
        return baseUrl + "/.well-known/oauth-protected-resource";
    }

    private static String encode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
