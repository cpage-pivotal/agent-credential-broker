package org.tanzu.broker.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.tanzu.broker.token.TokenStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuthCallbackController {

    private final OAuthFlowService oAuthFlowService;
    private final TokenStore tokenStore;

    public OAuthCallbackController(OAuthFlowService oAuthFlowService, TokenStore tokenStore) {
        this.oAuthFlowService = oAuthFlowService;
        this.tokenStore = tokenStore;
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<String> handleCallback(
        @RequestParam String code,
        @RequestParam String state,
        HttpServletRequest request
    ) {
        var pendingState = oAuthFlowService.getPendingState(state);
        if (pendingState == null) {
            return ResponseEntity.badRequest().body("Unknown state");
        }

        var redirectUri = buildRedirectUri(request);
        var storedToken = oAuthFlowService.exchangeCodeForTokens(state, code, redirectUri);
        tokenStore.store(pendingState.userId(), pendingState.targetSystem(), storedToken);

        return ResponseEntity.ok("""
            <!DOCTYPE html>
            <html>
            <body>
            <script>
              window.opener.postMessage({ type: 'oauth-callback', status: 'success' }, '*');
              window.close();
            </script>
            <p>Authorization complete. This window will close automatically.</p>
            </body>
            </html>
            """);
    }

    private String buildRedirectUri(HttpServletRequest request) {
        var scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = request.getScheme();
        var host = request.getHeader("X-Forwarded-Host");
        if (host == null) host = request.getServerName() + ":" + request.getServerPort();
        return scheme + "://" + host + "/oauth/callback";
    }
}
