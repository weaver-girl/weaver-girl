package com.github.cc11001100.weavergirl.core;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Security utilities for the weaver-girl agent.
 *
 * <p>Provides methods for sanitizing sensitive data in logs and
 * validating security-critical inputs.</p>
 *
 * @since 1.0.0
 */
public final class SecurityUtils {

    /** Config keys that may contain sensitive values. */
    private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList(
            "password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
            "accesskey", "access_key", "privatekey", "private_key", "credential",
            "auth", "authorization"
    ));

    /** Pattern to detect sensitive key names (case-insensitive). */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i).*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|" +
            "private[_-]?key|credential|auth|authorization).*"
    );

    private SecurityUtils() {}

    /**
     * Check if a config key name may contain sensitive data.
     *
     * @param key the configuration key
     * @return true if the key likely contains sensitive data
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        // Check exact match
        if (SENSITIVE_KEYS.contains(lower)) return true;
        // Check partial match (e.g., "db.password", "redis.secret")
        for (String suffix : SENSITIVE_KEYS) {
            if (lower.endsWith("." + suffix) || lower.contains(suffix + ".")) {
                return true;
            }
        }
        return SENSITIVE_PATTERN.matcher(key).matches();
    }

    /**
     * Mask a potentially sensitive value for safe logging.
     * Returns "****" for sensitive values, original value otherwise.
     *
     * @param key   the configuration key
     * @param value the configuration value
     * @return the masked or original value
     */
    public static String maskIfSensitive(String key, String value) {
        if (value == null) return null;
        if (isSensitiveKey(key)) {
            return "****";
        }
        return value;
    }

    /**
     * Sanitize a map of configuration for safe logging.
     * Sensitive values are replaced with "****".
     *
     * @param config the configuration map
     * @return a sanitized copy suitable for logging
     */
    public static Map<String, String> sanitizeForLogging(Map<String, String> config) {
        if (config == null) return Collections.emptyMap();
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            sanitized.put(entry.getKey(), maskIfSensitive(entry.getKey(), entry.getValue()));
        }
        return sanitized;
    }

    /**
     * Validate a file path to prevent path traversal attacks.
     *
     * @param path     the path to validate
     * @param baseDir  the allowed base directory
     * @return the canonical path if safe
     * @throws SecurityException if path traversal is detected
     */
    public static String validatePath(String path, String baseDir) {
        try {
            java.io.File file = new java.io.File(path).getCanonicalFile();
            java.io.File base = new java.io.File(baseDir).getCanonicalFile();
            if (!file.getAbsolutePath().startsWith(base.getAbsolutePath())) {
                throw new SecurityException("Path traversal detected: " + path);
            }
            return file.getAbsolutePath();
        } catch (java.io.IOException e) {
            throw new SecurityException("Invalid path: " + path);
        }
    }
}
