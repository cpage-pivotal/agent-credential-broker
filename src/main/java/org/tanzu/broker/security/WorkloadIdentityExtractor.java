package org.tanzu.broker.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.authentication.preauth.x509.X509PrincipalExtractor;
import org.springframework.stereotype.Component;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts workload identity from Cloud Foundry Instance Identity certificates.
 * <p>
 * CF Instance Identity certs encode the workload's org, space, and app GUIDs
 * in the Organizational Unit (OU) fields of the subject DN:
 * <ul>
 *   <li>{@code OU=organization:ORG-GUID}</li>
 *   <li>{@code OU=space:SPACE-GUID}</li>
 *   <li>{@code OU=app:APP-GUID}</li>
 * </ul>
 * These are shared across all container instances of the same app, making them
 * the correct granularity for workload identity (unlike CN, which is per-instance).
 * <p>
 * The canonical workload identity format is:
 * {@code organization:ORG-GUID/space:SPACE-GUID/app:APP-GUID}
 * <p>
 * Falls back to extracting the Common Name (CN) when OU fields are missing,
 * supporting non-CF deployments and local development.
 */
@Component
public class WorkloadIdentityExtractor implements X509PrincipalExtractor {

    private static final Logger log = LoggerFactory.getLogger(WorkloadIdentityExtractor.class);

    private static final String OU_ATTRIBUTE = "OU";
    private static final String CN_ATTRIBUTE = "CN";
    private static final String ORG_PREFIX = "organization:";
    private static final String SPACE_PREFIX = "space:";
    private static final String APP_PREFIX = "app:";

    @Override
    public Object extractPrincipal(X509Certificate cert) {
        return extractWorkloadIdentity(cert);
    }

    /**
     * Extract the workload identity from a CF Instance Identity certificate.
     *
     * @param cert the X.509 certificate
     * @return canonical workload identity string, or CN as fallback
     */
    public String extractWorkloadIdentity(X509Certificate cert) {
        try {
            var dn = new LdapName(cert.getSubjectX500Principal().getName());
            log.debug("Certificate subject DN: {}", cert.getSubjectX500Principal().getName());
            Map<String, String> ouValues = new HashMap<>();
            String[] cn = {null};

            for (Rdn rdn : dn.getRdns()) {
                NamingEnumeration<? extends Attribute> allAttrs = rdn.toAttributes().getAll();
                while (allAttrs.hasMore()) {
                    Attribute attr = allAttrs.next();
                    String type = attr.getID().toUpperCase();
                    for (int i = 0; i < attr.size(); i++) {
                        String value = attr.get(i).toString();
                        if (OU_ATTRIBUTE.equals(type)) {
                            classifyOuValue(value, ouValues);
                        } else if (CN_ATTRIBUTE.equals(type) && cn[0] == null) {
                            cn[0] = value;
                        }
                    }
                }
            }

            if (ouValues.containsKey("organization") && ouValues.containsKey("space") && ouValues.containsKey("app")) {
                String identity = ouValues.get("organization") + "/" + ouValues.get("space") + "/" + ouValues.get("app");
                log.debug("Extracted CF workload identity: {}", identity);
                return identity;
            }

            if (cn[0] != null && !cn[0].isBlank()) {
                log.debug("No CF OU fields found, falling back to CN: {}", cn[0]);
                return cn[0];
            }

            throw new IllegalArgumentException("Certificate has no CF OU fields and no CN");
        } catch (NamingException e) {
            throw new IllegalArgumentException("Cannot parse certificate subject DN", e);
        }
    }

    private void classifyOuValue(String value, Map<String, String> ouValues) {
        if (value.startsWith(ORG_PREFIX)) {
            ouValues.put("organization", value);
        } else if (value.startsWith(SPACE_PREFIX)) {
            ouValues.put("space", value);
        } else if (value.startsWith(APP_PREFIX)) {
            ouValues.put("app", value);
        }
    }
}
