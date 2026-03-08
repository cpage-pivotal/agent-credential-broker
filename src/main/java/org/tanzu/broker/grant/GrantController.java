package org.tanzu.broker.grant;

import jakarta.servlet.http.HttpServletRequest;
import org.tanzu.broker.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/grants")
public class GrantController {

    private final GrantService grantService;

    public GrantController(GrantService grantService) {
        this.grantService = grantService;
    }

    @GetMapping
    public List<UserGrant> listGrants() {
        return grantService.listGrants(SecurityUtils.currentUserId());
    }

    @PostMapping("/{targetSystem}/authorize")
    public ResponseEntity<Map<String, String>> authorize(
        @PathVariable String targetSystem,
        @RequestParam(required = false) String scope,
        HttpServletRequest request
    ) {
        var redirectUri = buildRedirectUri(request);
        var initiation = grantService.initiateOAuthGrant(
            SecurityUtils.currentUserId(), targetSystem, redirectUri, scope
        );
        return ResponseEntity.ok(Map.of("authorizationUrl", initiation.authorizationUrl()));
    }

    @PostMapping("/{targetSystem}/token")
    public ResponseEntity<Void> storeToken(
        @PathVariable String targetSystem,
        @RequestBody Map<String, String> body
    ) {
        grantService.storeUserProvidedToken(SecurityUtils.currentUserId(), targetSystem, body.get("token"));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{targetSystem}")
    public ResponseEntity<Void> revokeGrant(@PathVariable String targetSystem) {
        grantService.revokeGrant(SecurityUtils.currentUserId(), targetSystem);
        return ResponseEntity.ok().build();
    }

    private String buildRedirectUri(HttpServletRequest request) {
        var scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = request.getScheme();
        var host = request.getHeader("X-Forwarded-Host");
        if (host == null) host = request.getServerName();
        host = host.replaceAll(":443$", "").replaceAll(":80$", "");
        return scheme + "://" + host + "/oauth/callback";
    }
}
