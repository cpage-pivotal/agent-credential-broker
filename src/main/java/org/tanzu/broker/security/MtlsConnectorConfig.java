package org.tanzu.broker.security;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Adds a second Tomcat connector that serves HTTPS with mutual TLS (mTLS)
 * using Cloud Foundry Instance Identity certificates.
 * <p>
 * The connector listens on the port specified by {@code broker.mtls.port}
 * (default 8443) and requires client certificates. This enables agents
 * connecting over C2C networking to present their Instance Identity
 * certificates, which the broker uses for workload identity verification.
 * <p>
 * The server's own TLS identity comes from {@code CF_INSTANCE_CERT} and
 * {@code CF_INSTANCE_KEY}. Client certificates are validated against the
 * JVM's default trust store, which the CF container security provider
 * populates with the Diego Instance Identity CA.
 */
@Configuration
@ConditionalOnProperty(name = "broker.mtls.enabled", havingValue = "true", matchIfMissing = true)
public class MtlsConnectorConfig {

    private static final Logger log = LoggerFactory.getLogger(MtlsConnectorConfig.class);
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    @Value("${broker.mtls.port:8443}")
    private int mtlsPort;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> mtlsConnectorCustomizer() {
        return factory -> {
            String certPath = System.getenv("CF_INSTANCE_CERT");
            String keyPath = System.getenv("CF_INSTANCE_KEY");

            if (certPath == null || keyPath == null) {
                log.info("CF_INSTANCE_CERT/CF_INSTANCE_KEY not set — mTLS connector not available (local dev)");
                return;
            }

            try {
                Path keystorePath = buildKeystoreFromPem(Path.of(certPath), Path.of(keyPath));

                Connector connector = new Connector(Http11NioProtocol.class.getName());
                connector.setPort(mtlsPort);
                connector.setScheme("https");
                connector.setSecure(true);
                connector.setProperty("SSLEnabled", "true");

                SSLHostConfig sslHostConfig = new SSLHostConfig();
                sslHostConfig.setProtocols("TLSv1.2+TLSv1.3");
                sslHostConfig.setCertificateVerification("required");

                SSLHostConfigCertificate cert = new SSLHostConfigCertificate(
                        sslHostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
                cert.setCertificateKeystoreFile(keystorePath.toAbsolutePath().toString());
                cert.setCertificateKeystorePassword(new String(KEYSTORE_PASSWORD));
                cert.setCertificateKeystoreType("PKCS12");
                sslHostConfig.addCertificate(cert);

                connector.addSslHostConfig(sslHostConfig);
                factory.addAdditionalTomcatConnectors(connector);

                log.info("mTLS connector configured on port {} (cert={}, clientAuth=required)", mtlsPort, certPath);
            } catch (Exception e) {
                log.error("Failed to configure mTLS connector: {}", e.getMessage(), e);
            }
        };
    }

    private static Path buildKeystoreFromPem(Path certPem, Path keyPem) throws Exception {
        List<X509Certificate> certs = readCertificateChain(certPem);
        PrivateKey privateKey = readPrivateKey(keyPem);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("instance-identity", privateKey, KEYSTORE_PASSWORD,
                certs.toArray(new X509Certificate[0]));

        Path keystorePath = Files.createTempFile("mtls-keystore-", ".p12");
        try (var fos = new FileOutputStream(keystorePath.toFile())) {
            keyStore.store(fos, KEYSTORE_PASSWORD);
        }
        return keystorePath;
    }

    private static List<X509Certificate> readCertificateChain(Path pemPath) throws Exception {
        String pem = Files.readString(pemPath);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certs = new ArrayList<>();

        String[] blocks = pem.split("-----END CERTIFICATE-----");
        for (String block : blocks) {
            int begin = block.indexOf("-----BEGIN CERTIFICATE-----");
            if (begin < 0) continue;
            String base64 = block.substring(begin + "-----BEGIN CERTIFICATE-----".length())
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
        }
        return certs;
    }

    private static PrivateKey readPrivateKey(Path pemPath) throws Exception {
        String pem = Files.readString(pemPath);

        if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            byte[] der = Base64.getDecoder().decode(extractBase64(pem, "RSA PRIVATE KEY"));
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1InPkcs8(der)));
        } else if (pem.contains("-----BEGIN EC PRIVATE KEY-----")) {
            byte[] der = Base64.getDecoder().decode(extractBase64(pem, "EC PRIVATE KEY"));
            return KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(wrapEcInPkcs8(der)));
        } else {
            byte[] der = Base64.getDecoder().decode(extractBase64(pem, "PRIVATE KEY"));
            try {
                return KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (Exception e) {
                return KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(der));
            }
        }
    }

    private static String extractBase64(String pem, String label) {
        String beginMarker = "-----BEGIN " + label + "-----";
        String endMarker = "-----END " + label + "-----";
        int start = pem.indexOf(beginMarker) + beginMarker.length();
        int end = pem.indexOf(endMarker);
        return pem.substring(start, end).replaceAll("\\s", "");
    }

    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1Key) {
        byte[] rsaOid = {
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        return buildPkcs8(rsaOid, pkcs1Key);
    }

    private static byte[] wrapEcInPkcs8(byte[] ecKey) {
        byte[] ecOidPrefix = {
                0x30, 0x13,
                0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01,
                0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07
        };
        return buildPkcs8(ecOidPrefix, ecKey);
    }

    private static byte[] buildPkcs8(byte[] algorithmId, byte[] privateKey) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] keyOctetString = wrapInTag((byte) 0x04, privateKey);
        byte[] inner = concat(version, algorithmId, keyOctetString);
        return wrapInTag((byte) 0x30, inner);
    }

    private static byte[] wrapInTag(byte tag, byte[] content) {
        byte[] length = derLength(content.length);
        byte[] result = new byte[1 + length.length + content.length];
        result[0] = tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(content, 0, result, 1 + length.length, content.length);
        return result;
    }

    private static byte[] derLength(int length) {
        if (length < 128) return new byte[]{(byte) length};
        if (length < 256) return new byte[]{(byte) 0x81, (byte) length};
        return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) (length & 0xff)};
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}
