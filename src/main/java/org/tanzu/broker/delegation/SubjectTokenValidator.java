package org.tanzu.broker.delegation;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Validates the RFC 8693 subject_token (a user JWT issued by the corporate IdP)
 * using the same JWKS endpoint configured for the OAuth2 resource server.
 */
@Component
public class SubjectTokenValidator {

    private final JwtDecoder jwtDecoder;

    public SubjectTokenValidator(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * Validate the subject token and extract the user identity.
     *
     * @param subjectToken raw JWT string
     * @return validated result containing user ID, or an error description
     */
    public Result validate(String subjectToken) {
        try {
            Jwt jwt = jwtDecoder.decode(subjectToken);
            String userId = jwt.getSubject();
            if (userId == null || userId.isBlank()) {
                return Result.failure("subject_token JWT has no 'sub' claim");
            }
            return Result.success(userId);
        } catch (JwtException e) {
            return Result.failure("Invalid subject_token: " + e.getMessage());
        }
    }

    public record Result(String userId, String error) {

        public static Result success(String userId) {
            return new Result(userId, null);
        }

        public static Result failure(String error) {
            return new Result(null, error);
        }

        public boolean isValid() {
            return userId != null;
        }

        public Optional<String> getUserId() {
            return Optional.ofNullable(userId);
        }
    }
}
