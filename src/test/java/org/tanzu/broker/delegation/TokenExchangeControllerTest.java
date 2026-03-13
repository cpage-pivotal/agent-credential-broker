package org.tanzu.broker.delegation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenExchangeControllerTest {

    private DelegationTokenService delegationTokenService;
    private SubjectTokenValidator subjectTokenValidator;
    private TokenExchangeController controller;

    @BeforeEach
    void setUp() {
        delegationTokenService = mock(DelegationTokenService.class);
        subjectTokenValidator = mock(SubjectTokenValidator.class);
        controller = new TokenExchangeController(delegationTokenService, subjectTokenValidator);
    }

    @Test
    void successfulTokenExchange() {
        when(subjectTokenValidator.validate("valid-user-jwt"))
                .thenReturn(SubjectTokenValidator.Result.success("user-123"));

        var delegation = new Delegation(
                "deleg-abc", "user-123", "my-agent",
                List.of("github", "cloud-foundry"),
                Instant.now(), Instant.now().plus(Duration.ofHours(72)), false
        );
        when(delegationTokenService.createDelegation(eq("user-123"), eq("my-agent"),
                eq(List.of("github", "cloud-foundry")), isNull()))
                .thenReturn(new DelegationTokenService.DelegationTokenResult(delegation, "signed-jwt-token"));

        Authentication auth = new TestingAuthenticationToken("my-agent", null);

        var response = controller.tokenExchange(
                TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE,
                "valid-user-jwt",
                TokenExchangeRequest.TOKEN_TYPE_JWT,
                "github cloud-foundry",
                null, null,
                auth
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(TokenExchangeResponse.class, response.getBody());
        var body = (TokenExchangeResponse) response.getBody();
        assertEquals("signed-jwt-token", body.accessToken());
        assertEquals("Bearer", body.tokenType());
        assertEquals(TokenExchangeRequest.TOKEN_TYPE_JWT, body.issuedTokenType());
        assertTrue(body.expiresIn() > 0);
    }

    @Test
    void rejectsInvalidGrantType() {
        Authentication auth = new TestingAuthenticationToken("agent", null);

        var response = controller.tokenExchange(
                "authorization_code",
                "token", TokenExchangeRequest.TOKEN_TYPE_JWT,
                "github", null, null, auth
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertInstanceOf(TokenExchangeError.class, response.getBody());
        assertEquals("invalid_request", ((TokenExchangeError) response.getBody()).error());
    }

    @Test
    void rejectsInvalidSubjectTokenType() {
        Authentication auth = new TestingAuthenticationToken("agent", null);

        var response = controller.tokenExchange(
                TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE,
                "token", "urn:ietf:params:oauth:token-type:access_token",
                "github", null, null, auth
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertInstanceOf(TokenExchangeError.class, response.getBody());
        assertEquals("invalid_request", ((TokenExchangeError) response.getBody()).error());
    }

    @Test
    void rejectsBlankSubjectToken() {
        Authentication auth = new TestingAuthenticationToken("agent", null);

        var response = controller.tokenExchange(
                TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE,
                "   ", TokenExchangeRequest.TOKEN_TYPE_JWT,
                "github", null, null, auth
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void rejectsInvalidSubjectTokenJwt() {
        when(subjectTokenValidator.validate("bad-jwt"))
                .thenReturn(SubjectTokenValidator.Result.failure("JWT expired"));

        Authentication auth = new TestingAuthenticationToken("agent", null);

        var response = controller.tokenExchange(
                TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE,
                "bad-jwt", TokenExchangeRequest.TOKEN_TYPE_JWT,
                "github", null, null, auth
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertInstanceOf(TokenExchangeError.class, response.getBody());
        assertEquals("invalid_grant", ((TokenExchangeError) response.getBody()).error());
    }

    @Test
    void rejectsMissingScope() {
        when(subjectTokenValidator.validate("valid-jwt"))
                .thenReturn(SubjectTokenValidator.Result.success("user-123"));

        Authentication auth = new TestingAuthenticationToken("agent", null);

        var response = controller.tokenExchange(
                TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE,
                "valid-jwt", TokenExchangeRequest.TOKEN_TYPE_JWT,
                null, null, null, auth
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertInstanceOf(TokenExchangeError.class, response.getBody());
        assertEquals("invalid_request", ((TokenExchangeError) response.getBody()).error());
    }

    @Test
    void rejectsBlankWorkloadIdentity() {
        when(subjectTokenValidator.validate("valid-jwt"))
                .thenReturn(SubjectTokenValidator.Result.success("user-123"));

        Authentication auth = new TestingAuthenticationToken("", null);

        var response = controller.tokenExchange(
                TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE,
                "valid-jwt", TokenExchangeRequest.TOKEN_TYPE_JWT,
                "github", null, null, auth
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertInstanceOf(TokenExchangeError.class, response.getBody());
        assertEquals("invalid_grant", ((TokenExchangeError) response.getBody()).error());
    }

    @Test
    void parsesMultipleSystemsFromScope() {
        when(subjectTokenValidator.validate("valid-jwt"))
                .thenReturn(SubjectTokenValidator.Result.success("user-123"));

        var delegation = new Delegation(
                "deleg-xyz", "user-123", "agent",
                List.of("github", "cloud-foundry", "mapbox"),
                Instant.now(), Instant.now().plus(Duration.ofHours(72)), false
        );
        when(delegationTokenService.createDelegation(eq("user-123"), eq("agent"),
                eq(List.of("github", "cloud-foundry", "mapbox")), isNull()))
                .thenReturn(new DelegationTokenService.DelegationTokenResult(delegation, "token"));

        Authentication auth = new TestingAuthenticationToken("agent", null);

        var response = controller.tokenExchange(
                TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE,
                "valid-jwt", TokenExchangeRequest.TOKEN_TYPE_JWT,
                "github  cloud-foundry   mapbox", null, null, auth
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());

        verify(delegationTokenService).createDelegation("user-123", "agent",
                List.of("github", "cloud-foundry", "mapbox"), null);
    }
}
