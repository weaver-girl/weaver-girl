package com.github.cc11001100.weavergirl.core.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Release metadata and integrity verification for the agent.
 *
 * <p>Loaded from {@code META-INF/weaver-girl-release.properties} which is
 * generated during the build process. Contains:</p>
 * <ul>
 *   <li>Version (semantic versioning)</li>
 *   <li>Build timestamp</li>
 *   <li>Git commit hash</li>
 *   <li>Build number (CI)</li>
 *   <li>SHA-256 checksum for integrity verification</li>
 * </ul>
 */
public final class ReleaseInfo {

    private static final Logger log = LoggerFactory.getLogger(ReleaseInfo.class);

    private static final String PROPERTIES_PATH = "META-INF/weaver-girl-release.properties";

    private static final ReleaseInfo INSTANCE = load();

    private final String version;
    private final String buildTimestamp;
    private final String gitCommit;
    private final String buildNumber;
    private final String checksum;

    private ReleaseInfo(String version, String buildTimestamp, String gitCommit,
                        String buildNumber, String checksum) {
        this.version = version;
        this.buildTimestamp = buildTimestamp;
        this.gitCommit = gitCommit;
        this.buildNumber = buildNumber;
        this.checksum = checksum;
    }

    /**
     * Get the singleton release info instance.
     */
    public static ReleaseInfo getInstance() {
        return INSTANCE;
    }

    /** Agent version (e.g. "1.0.0"). */
    public String getVersion() { return version; }

    /** Build timestamp (ISO-8601). */
    public String getBuildTimestamp() { return buildTimestamp; }

    /** Git commit hash (short). */
    public String getGitCommit() { return gitCommit; }

    /** CI build number. */
    public String getBuildNumber() { return buildNumber; }

    /** SHA-256 checksum of the agent JAR. */
    public String getChecksum() { return checksum; }

    /**
     * Verify the integrity of the agent JAR by comparing checksums.
     *
     * @param expectedChecksum the expected SHA-256 checksum
     * @return true if the checksum matches
     */
    public boolean verifyIntegrity(String expectedChecksum) {
        if (expectedChecksum == null || checksum == null) {
            log.warn("[Release] Cannot verify integrity: missing checksum");
            return false;
        }
        boolean valid = checksum.equalsIgnoreCase(expectedChecksum);
        if (valid) {
            log.info("[Release] Integrity check passed");
        } else {
            log.error("[Release] INTEGRITY CHECK FAILED! Expected: {}, Actual: {}",
                    expectedChecksum, checksum);
        }
        return valid;
    }

    /**
     * Get a human-readable release summary.
     */
    public String getSummary() {
        return String.format("Weaver-Girl Agent v%s (build %s, commit %s, %s)",
                version, buildNumber, gitCommit, buildTimestamp);
    }

    /**
     * Get all release properties as a map.
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("version", version);
        map.put("buildTimestamp", buildTimestamp);
        map.put("gitCommit", gitCommit);
        map.put("buildNumber", buildNumber);
        map.put("checksum", checksum);
        return map;
    }

    @Override
    public String toString() {
        return getSummary();
    }

    // --- Loading ---

    private static ReleaseInfo load() {
        try {
            ClassLoader cl = ReleaseInfo.class.getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            URL url = cl.getResource(PROPERTIES_PATH);
            if (url != null) {
                try (InputStream is = url.openStream();
                     InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    Properties props = new Properties();
                    props.load(reader);
                    return new ReleaseInfo(
                            props.getProperty("version", "unknown"),
                            props.getProperty("buildTimestamp", "unknown"),
                            props.getProperty("gitCommit", "unknown"),
                            props.getProperty("buildNumber", "0"),
                            props.getProperty("checksum", "")
                    );
                }
            }
        } catch (IOException e) {
            log.warn("[Release] Failed to load release info: {}", e.getMessage());
        }

        // Default for development builds
        return new ReleaseInfo("1.0.0-SNAPSHOT", "dev", "dev", "0", "");
    }

    // --- Utility ---

    /**
     * Compute SHA-256 hash of a byte array.
     */
    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
