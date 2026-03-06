package org.tanzu.broker.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new IllegalStateException("No authentication in context");
        }

        if (auth.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser.getSubject();
        }
        if (auth.getPrincipal() instanceof OAuth2User oAuth2User) {
            var sub = oAuth2User.getAttributes().get("sub");
            if (sub != null) return String.valueOf(sub);
            return oAuth2User.getName();
        }

        return auth.getName();
    }
}
