package org.tanzu.broker.cf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tanzu.broker.security.SecurityUtils;
import org.tanzu.broker.token.TokenLifecycleService;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Resolves Cloud Foundry org/space/app names to GUIDs using the CF v3 API.
 * Uses the logged-in user's stored Cloud Foundry access token (from the
 * {@code cloud-foundry} grant) to make API calls.
 */
@RestController
@RequestMapping("/api/cf")
@EnableConfigurationProperties(CfApiProperties.class)
public class CfLookupController {

    private static final Logger log = LoggerFactory.getLogger(CfLookupController.class);
    private static final String CF_TARGET_SYSTEM = "cloud-foundry";

    private final TokenLifecycleService tokenLifecycleService;
    private final CfApiProperties cfApiProperties;
    private final ObjectMapper objectMapper;
    private HttpClient httpClient;

    public CfLookupController(TokenLifecycleService tokenLifecycleService,
                              CfApiProperties cfApiProperties) {
        this.tokenLifecycleService = tokenLifecycleService;
        this.cfApiProperties = cfApiProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public record ResolvedWorkload(
            String orgName, String orgGuid,
            String spaceName, String spaceGuid,
            String appName, String appGuid
    ) {}

    @GetMapping("/resolve-workload")
    public ResponseEntity<?> resolveWorkload(
            @RequestParam("org") String orgName,
            @RequestParam("space") String spaceName,
            @RequestParam("app") String appName
    ) {
        if (cfApiProperties.baseUrl() == null || cfApiProperties.baseUrl().isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "CF API URL is not configured"));
        }

        String userId = SecurityUtils.currentUserId();
        var tokenOpt = tokenLifecycleService.getValidToken(userId, CF_TARGET_SYSTEM);
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Connect the Cloud Foundry grant first to resolve workload names"));
        }

        String accessToken = tokenOpt.get().accessToken();
        String baseUrl = cfApiProperties.baseUrl().endsWith("/")
                ? cfApiProperties.baseUrl().substring(0, cfApiProperties.baseUrl().length() - 1)
                : cfApiProperties.baseUrl();

        try {
            String orgGuid = resolveGuid(baseUrl, accessToken,
                    "/v3/organizations?names=" + encode(orgName));
            if (orgGuid == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Organization '" + orgName + "' not found"));
            }

            String spaceGuid = resolveGuid(baseUrl, accessToken,
                    "/v3/spaces?names=" + encode(spaceName) + "&organization_guids=" + encode(orgGuid));
            if (spaceGuid == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Space '" + spaceName + "' not found in organization '" + orgName + "'"));
            }

            String appGuid = resolveGuid(baseUrl, accessToken,
                    "/v3/apps?names=" + encode(appName) + "&space_guids=" + encode(spaceGuid));
            if (appGuid == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "App '" + appName + "' not found in space '" + spaceName + "'"));
            }

            log.info("Resolved CF workload: {}/{}/{} -> {}/{}/{}", orgName, spaceName, appName, orgGuid, spaceGuid, appGuid);
            return ResponseEntity.ok(new ResolvedWorkload(orgName, orgGuid, spaceName, spaceGuid, appName, appGuid));

        } catch (Exception e) {
            log.error("Failed to resolve CF workload names: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Failed to contact Cloud Foundry API: " + e.getMessage()));
        }
    }

    private String resolveGuid(String baseUrl, String accessToken, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("CF API returned HTTP {} for {}", response.statusCode(), path);
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode resources = root.get("resources");
        if (resources == null || resources.isEmpty()) {
            return null;
        }

        return resources.get(0).get("guid").asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
