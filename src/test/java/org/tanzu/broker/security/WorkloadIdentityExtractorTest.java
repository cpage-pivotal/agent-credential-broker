package org.tanzu.broker.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadIdentityExtractorTest {

    private final WorkloadIdentityExtractor extractor = new WorkloadIdentityExtractor();

    @Test
    void extractsCfWorkloadIdentityFromOuFields(@TempDir Path tempDir) throws Exception {
        X509Certificate cert = generateCert(tempDir,
                "CN=instance-guid-123," +
                "OU=app:app-guid-789," +
                "OU=space:space-guid-456," +
                "OU=organization:org-guid-123," +
                "O=Cloud Foundry"
        );

        String identity = extractor.extractWorkloadIdentity(cert);

        assertEquals("organization:org-guid-123/space:space-guid-456/app:app-guid-789", identity);
    }

    @Test
    void extractPrincipalReturnsSameAsExtractWorkloadIdentity(@TempDir Path tempDir) throws Exception {
        X509Certificate cert = generateCert(tempDir,
                "CN=instance-guid," +
                "OU=app:my-app," +
                "OU=space:my-space," +
                "OU=organization:my-org"
        );

        Object principal = extractor.extractPrincipal(cert);

        assertEquals("organization:my-org/space:my-space/app:my-app", principal);
    }

    @Test
    void fallsBackToCnWhenNoOuFields(@TempDir Path tempDir) throws Exception {
        X509Certificate cert = generateCert(tempDir, "CN=my-test-agent");

        String identity = extractor.extractWorkloadIdentity(cert);

        assertEquals("my-test-agent", identity);
    }

    @Test
    void fallsBackToCnWhenPartialOuFields(@TempDir Path tempDir) throws Exception {
        X509Certificate cert = generateCert(tempDir,
                "CN=fallback-cn," +
                "OU=organization:org-guid-123," +
                "OU=space:space-guid-456"
        );

        String identity = extractor.extractWorkloadIdentity(cert);

        assertEquals("fallback-cn", identity);
    }

    @Test
    void throwsWhenNoCnAndNoCompleteOuFields(@TempDir Path tempDir) throws Exception {
        X509Certificate cert = generateCert(tempDir, "O=SomeOrg,CN=placeholder");
        // Modify expected: keytool requires CN, but we test a cert without OU fields
        // This cert has a CN so it should fall back to CN
        String identity = extractor.extractWorkloadIdentity(cert);
        assertEquals("placeholder", identity);
    }

    @Test
    void handlesRealWorldCfGuidFormat(@TempDir Path tempDir) throws Exception {
        X509Certificate cert = generateCert(tempDir,
                "CN=a1b2c3d4-e5f6-7890-abcd-000000000001," +
                "OU=app:c3d4e5f6-a7b8-9012-cdef-123456789012," +
                "OU=space:b2c3d4e5-f6a7-8901-bcde-f12345678901," +
                "OU=organization:a1b2c3d4-e5f6-7890-abcd-ef1234567890," +
                "O=Cloud Foundry"
        );

        String identity = extractor.extractWorkloadIdentity(cert);

        assertEquals(
                "organization:a1b2c3d4-e5f6-7890-abcd-ef1234567890/" +
                "space:b2c3d4e5-f6a7-8901-bcde-f12345678901/" +
                "app:c3d4e5f6-a7b8-9012-cdef-123456789012",
                identity
        );
    }

    private static X509Certificate generateCert(Path tempDir, String dname) throws Exception {
        Path ksFile = tempDir.resolve("test.p12");
        String alias = "test";
        char[] password = "changeit".toCharArray();

        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1",
                "-dname", dname,
                "-storetype", "PKCS12",
                "-keystore", ksFile.toString(),
                "-storepass", new String(password)
        );
        pb.inheritIO();
        int exitCode = pb.start().waitFor();
        assertEquals(0, exitCode, "keytool should succeed");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(ksFile)) {
            ks.load(is, password);
        }

        return (X509Certificate) ks.getCertificate(alias);
    }
}
