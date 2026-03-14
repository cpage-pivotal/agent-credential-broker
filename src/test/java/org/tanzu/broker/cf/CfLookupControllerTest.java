package org.tanzu.broker.cf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tanzu.broker.token.StoredToken;
import org.tanzu.broker.token.TokenLifecycleService;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CfLookupControllerTest {

    private TokenLifecycleService tokenLifecycleService;
    private HttpClient httpClient;
    private CfLookupController controller;

    private static final String CF_API_URL = "https://api.sys.example.com";
    private static final String USER_ID = "test-user-123";
    private static final String CF_TOKEN = "cf-access-token";

    @BeforeEach
    void setUp() {
        tokenLifecycleService = mock(TokenLifecycleService.class);
        httpClient = mock(HttpClient.class);
        var properties = new CfApiProperties(CF_API_URL);
        controller = new CfLookupController(tokenLifecycleService, properties);
        controller.setHttpClient(httpClient);

        var auth = new TestingAuthenticationToken(USER_ID, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesWorkloadSuccessfully() throws Exception {
        setupCfToken();

        var orgResponse = mockHttpResponse(200, """
            {"resources": [{"guid": "org-guid-111", "name": "my-org"}]}
            """);
        var spaceResponse = mockHttpResponse(200, """
            {"resources": [{"guid": "space-guid-222", "name": "dev"}]}
            """);
        var appResponse = mockHttpResponse(200, """
            {"resources": [{"guid": "app-guid-333", "name": "goose-agent"}]}
            """);

        doReturn(orgResponse, spaceResponse, appResponse)
                .when(httpClient).send(any(HttpRequest.class), any());

        var response = controller.resolveWorkload("my-org", "dev", "goose-agent");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (CfLookupController.ResolvedWorkload) response.getBody();
        assertNotNull(body);
        assertEquals("org-guid-111", body.orgGuid());
        assertEquals("space-guid-222", body.spaceGuid());
        assertEquals("app-guid-333", body.appGuid());
        assertEquals("my-org", body.orgName());
        assertEquals("dev", body.spaceName());
        assertEquals("goose-agent", body.appName());
    }

    @Test
    void returnsBadRequestWhenNoCfTokenAvailable() {
        when(tokenLifecycleService.getValidToken(USER_ID, "cloud-foundry")).thenReturn(Optional.empty());

        var response = controller.resolveWorkload("my-org", "dev", "goose-agent");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("error").contains("Connect the Cloud Foundry grant"));
    }

    @Test
    void returnsNotFoundWhenOrgNameNotFound() throws Exception {
        setupCfToken();

        var emptyResponse = mockHttpResponse(200, """
            {"resources": []}
            """);

        doReturn(emptyResponse).when(httpClient).send(any(HttpRequest.class), any());

        var response = controller.resolveWorkload("nonexistent-org", "dev", "goose-agent");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("error").contains("nonexistent-org"));
    }

    @Test
    void returnsNotFoundWhenSpaceNameNotFound() throws Exception {
        setupCfToken();

        var orgResponse = mockHttpResponse(200, """
            {"resources": [{"guid": "org-guid-111", "name": "my-org"}]}
            """);
        var emptyResponse = mockHttpResponse(200, """
            {"resources": []}
            """);

        doReturn(orgResponse, emptyResponse)
                .when(httpClient).send(any(HttpRequest.class), any());

        var response = controller.resolveWorkload("my-org", "nonexistent-space", "goose-agent");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("error").contains("nonexistent-space"));
    }

    @Test
    void returnsNotFoundWhenAppNameNotFound() throws Exception {
        setupCfToken();

        var orgResponse = mockHttpResponse(200, """
            {"resources": [{"guid": "org-guid-111", "name": "my-org"}]}
            """);
        var spaceResponse = mockHttpResponse(200, """
            {"resources": [{"guid": "space-guid-222", "name": "dev"}]}
            """);
        var emptyResponse = mockHttpResponse(200, """
            {"resources": []}
            """);

        doReturn(orgResponse, spaceResponse, emptyResponse)
                .when(httpClient).send(any(HttpRequest.class), any());

        var response = controller.resolveWorkload("my-org", "dev", "nonexistent-app");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("error").contains("nonexistent-app"));
    }

    @Test
    void returnsServiceUnavailableWhenCfApiUrlNotConfigured() {
        controller = new CfLookupController(tokenLifecycleService, new CfApiProperties(""));
        controller.setHttpClient(httpClient);

        var response = controller.resolveWorkload("my-org", "dev", "goose-agent");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void returnsBadGatewayWhenCfApiCallFails() throws Exception {
        setupCfToken();

        doThrow(new java.io.IOException("Connection refused"))
                .when(httpClient).send(any(HttpRequest.class), any());

        var response = controller.resolveWorkload("my-org", "dev", "goose-agent");

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    private void setupCfToken() {
        var token = new StoredToken(CF_TOKEN, null, Instant.now().plusSeconds(3600), "Authorization", "Bearer %s");
        when(tokenLifecycleService.getValidToken(USER_ID, "cloud-foundry")).thenReturn(Optional.of(token));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockHttpResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
