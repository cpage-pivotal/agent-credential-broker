package org.tanzu.broker.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates that a credential request originates from a platform workload
 * by checking the X-Forwarded-Client-Cert (XFCC) header injected by GoRouter.
 * <p>
 * GoRouter performs mTLS termination and sets the XFCC header when the calling
 * app presents a valid CF instance identity certificate signed by the platform CA.
 * A non-empty, well-formed XFCC header proves the caller is a workload running
 * on the platform — not an external client replaying a stolen delegation token.
 * <p>
 * This validator checks for the presence and basic structure of the XFCC header.
 * It does not verify <em>which</em> workload is calling (that is Phase 4b).
 */
@Component
public class WorkloadIdentityValidator {

    private static final Logger log = LoggerFactory.getLogger(WorkloadIdentityValidator.class);

    /**
     * Returns true if the XFCC header indicates a valid platform workload identity.
     * GoRouter URL-encodes the client certificate into the {@code Cert} field.
     */
    public boolean isValidPlatformWorkload(String xfccHeader) {
        if (xfccHeader == null || xfccHeader.isBlank()) {
            log.debug("XFCC header is absent or empty");
            return false;
        }

        // GoRouter sets XFCC in the form: Hash=...;Cert="...";Subject="...";...
        // A minimal check: the header must contain a Cert field with content.
        if (!xfccHeader.contains("Cert=")) {
            log.debug("XFCC header present but missing Cert field: {}", truncate(xfccHeader));
            return false;
        }

        log.debug("Valid platform workload identity in XFCC header");
        return true;
    }

    private static String truncate(String value) {
        return value.length() > 80 ? value.substring(0, 80) + "..." : value;
    }
}
