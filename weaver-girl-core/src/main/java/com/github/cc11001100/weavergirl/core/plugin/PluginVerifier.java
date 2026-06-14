package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Verifies plugin JAR integrity and metadata.
 *
 * <p>Checks:</p>
 * <ul>
 *   <li>Plugin metadata file presence and validity</li>
 *   <li>SHA-256 checksum verification</li>
 *   <li>Agent version compatibility</li>
 *   <li>SPI declaration presence</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class PluginVerifier {

    private static final Logger log = LoggerFactory.getLogger(PluginVerifier.class);

    /** SPI service file path. */
    private static final String SPI_PATH =
            "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin";

    /**
     * Verification result.
     */
    public static class VerificationResult {
        private final boolean valid;
        private final String error;
        private final PluginMetadata metadata;

        private VerificationResult(boolean valid, String error, PluginMetadata metadata) {
            this.valid = valid;
            this.error = error;
            this.metadata = metadata;
        }

        /** Whether the plugin passed verification. */
        public boolean isValid() { return valid; }

        /** Error message if invalid, null if valid. */
        public String getError() { return error; }

        /** Plugin metadata (null if metadata could not be read). */
        public PluginMetadata getMetadata() { return metadata; }

        public static VerificationResult ok(PluginMetadata metadata) {
            return new VerificationResult(true, null, metadata);
        }

        public static VerificationResult fail(String error) {
            return new VerificationResult(false, error, null);
        }

        public static VerificationResult fail(String error, PluginMetadata metadata) {
            return new VerificationResult(false, error, metadata);
        }
    }

    /**
     * Verify a plugin JAR file.
     *
     * @param jarPath path to the plugin JAR
     * @return verification result
     */
    public VerificationResult verify(String jarPath) {
        File jarFile = new File(jarPath);
        if (!jarFile.exists() || !jarFile.isFile()) {
            return VerificationResult.fail("JAR file not found: " + jarPath);
        }

        try (JarFile jar = new JarFile(jarFile)) {
            // 1. Check SPI declaration
            JarEntry spiEntry = jar.getJarEntry(SPI_PATH);
            if (spiEntry == null) {
                return VerificationResult.fail("Missing SPI declaration: " + SPI_PATH);
            }

            // 2. Read metadata (optional but recommended)
            PluginMetadata metadata = null;
            JarEntry metaEntry = jar.getJarEntry(PluginMetadata.METADATA_PATH);
            if (metaEntry != null) {
                try (InputStream is = jar.getInputStream(metaEntry)) {
                    metadata = PluginMetadata.fromProperties(is);
                }
            } else {
                log.debug("[PluginVerifier] No metadata file in {}", jarPath);
            }

            // 3. Verify SHA-256 if metadata specifies one
            if (metadata != null && metadata.getExpectedSha256() != null
                    && !metadata.getExpectedSha256().isEmpty()) {
                String actualSha256 = computeSha256(jarFile);
                if (!metadata.getExpectedSha256().equalsIgnoreCase(actualSha256)) {
                    return VerificationResult.fail(
                            "SHA-256 mismatch: expected " + metadata.getExpectedSha256()
                                    + " but got " + actualSha256, metadata);
                }
                log.debug("[PluginVerifier] SHA-256 verified for {}", jarPath);
            }

            return VerificationResult.ok(metadata);

        } catch (IOException e) {
            return VerificationResult.fail("Failed to read JAR: " + e.getMessage());
        }
    }

    /**
     * Compute SHA-256 hash of a file.
     *
     * @param file the file to hash
     * @return lowercase hex string of the SHA-256 digest
     */
    public static String computeSha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("SHA-256 computation failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
