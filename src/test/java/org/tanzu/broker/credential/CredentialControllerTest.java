package org.tanzu.broker.credential;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tanzu.broker.delegation.DelegationTokenService;
import org.tanzu.broker.grant.GrantService;
import org.tanzu.broker.targetsystem.TargetSystem;
import org.tanzu.broker.targetsystem.TargetSystemRegistry;
import org.tanzu.broker.token.TokenLifecycleService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialControllerTest {

    private DelegationTokenService delegationTokenService;
    private GrantService grantService;
    private TargetSystemRegistry registry;
    private TokenLifecycleService tokenLifecycleService;
    private CredentialController controller;

    private static final String WORKLOAD_IDENTITY = "organization:org-123/space:space-456/app:app-789";

    @BeforeEach
    void setUp() {
        delegationTokenService = mock(DelegationTokenService.class);
        grantService = mock(GrantService.class);
        registry = mock(TargetSystemRegistry.class);
        tokenLifecycleService = mock(TokenLifecycleService.class);
        controller = new CredentialController(delegationTokenService, grantService, registry, tokenLifecycleService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWhenCertIdentityDoesNotMatchDelegationAgent() {
        DecodedJWT jwt = mockJwt(WORKLOAD_IDENTITY, List.of("github"));
        when(delegationTokenService.validateDelegationToken("valid-token")).thenReturn(jwt);
        when(delegationTokenService.isRevoked("deleg-123")).thenReturn(false);

        var differentIdentity = new TestingAuthenticationToken(
                "organization:other-org/space:other-space/app:other-app", null);
        differentIdentity.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(differentIdentity);

        var response = controller.requestCredential(
                "Bearer valid-token",
                new CredentialRequest("github", null)
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void allowsRequestWhenCertIdentityMatchesDelegationAgent() {
        DecodedJWT jwt = mockJwt(WORKLOAD_IDENTITY, List.of("github"));
        when(delegationTokenService.validateDelegationToken("valid-token")).thenReturn(jwt);
        when(delegationTokenService.isRevoked("deleg-123")).thenReturn(false);

        var matchingIdentity = new TestingAuthenticationToken(WORKLOAD_IDENTITY, null);
        matchingIdentity.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(matchingIdentity);

        var ts = mock(TargetSystem.class);
        when(ts.isStaticApiKey()).thenReturn(true);
        when(ts.apiKey()).thenReturn("test-key");
        when(ts.resolvedHeaderName()).thenReturn("Authorization");
        when(ts.formatHeaderValue("test-key")).thenReturn("Bearer test-key");
        when(registry.get("github")).thenReturn(Optional.of(ts));

        var response = controller.requestCredential(
                "Bearer valid-token",
                new CredentialRequest("github", null)
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(ResourceAccessToken.class, response.getBody());
    }

    @Test
    void rejectsRequestWhenNoCertPresentedAndAgentClaimExists() {
        DecodedJWT jwt = mockJwt(WORKLOAD_IDENTITY, List.of("github"));
        when(delegationTokenService.validateDelegationToken("valid-token")).thenReturn(jwt);
        when(delegationTokenService.isRevoked("deleg-123")).thenReturn(false);

        SecurityContextHolder.clearContext();

        var response = controller.requestCredential(
                "Bearer valid-token",
                new CredentialRequest("github", null)
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void rejectsRequestWhenAnonymousUserAndAgentClaimExists() {
        DecodedJWT jwt = mockJwt(WORKLOAD_IDENTITY, List.of("github"));
        when(delegationTokenService.validateDelegationToken("valid-token")).thenReturn(jwt);
        when(delegationTokenService.isRevoked("deleg-123")).thenReturn(false);

        var anonymous = new TestingAuthenticationToken("anonymousUser", null);
        anonymous.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(anonymous);

        var response = controller.requestCredential(
                "Bearer valid-token",
                new CredentialRequest("github", null)
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void rejectsRequestWhenCertIsNotCfWorkloadIdentityAndAgentClaimExists() {
        DecodedJWT jwt = mockJwt(WORKLOAD_IDENTITY, List.of("github"));
        when(delegationTokenService.validateDelegationToken("valid-token")).thenReturn(jwt);
        when(delegationTokenService.isRevoked("deleg-123")).thenReturn(false);

        var routerCert = new TestingAuthenticationToken("ee1deb54-c9ce-4020-7f62-2a3e", null);
        routerCert.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(routerCert);

        var response = controller.requestCredential(
                "Bearer valid-token",
                new CredentialRequest("github", null)
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void allowsRequestWithoutAgentClaimAndNoCert() {
        DecodedJWT jwt = mockJwt(null, List.of("github"));
        when(delegationTokenService.validateDelegationToken("valid-token")).thenReturn(jwt);
        when(delegationTokenService.isRevoked("deleg-123")).thenReturn(false);

        SecurityContextHolder.clearContext();

        var ts = mock(TargetSystem.class);
        when(ts.isStaticApiKey()).thenReturn(true);
        when(ts.apiKey()).thenReturn("test-key");
        when(ts.resolvedHeaderName()).thenReturn("Authorization");
        when(ts.formatHeaderValue("test-key")).thenReturn("Bearer test-key");
        when(registry.get("github")).thenReturn(Optional.of(ts));

        var response = controller.requestCredential(
                "Bearer valid-token",
                new CredentialRequest("github", null)
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private DecodedJWT mockJwt(String agentId, List<String> systems) {
        DecodedJWT jwt = mock(DecodedJWT.class);
        when(jwt.getId()).thenReturn("deleg-123");
        when(jwt.getSubject()).thenReturn("user-abc");

        Claim agentClaim = mock(Claim.class);
        when(agentClaim.asString()).thenReturn(agentId);
        when(jwt.getClaim("agent")).thenReturn(agentClaim);

        Claim systemsClaim = mock(Claim.class);
        when(systemsClaim.asList(String.class)).thenReturn(systems);
        when(jwt.getClaim("systems")).thenReturn(systemsClaim);

        return jwt;
    }
}
