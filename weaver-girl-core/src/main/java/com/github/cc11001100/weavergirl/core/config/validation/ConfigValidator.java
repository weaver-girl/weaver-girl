package com.github.cc11001100.weavergirl.core.config.validation;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates agent configuration against known rules.
 *
 * @since 1.2.0
 */
public class ConfigValidator {

    private static final Set<String> VALID_BOOLEAN_VALUES = new HashSet<>(
            Arrays.asList("true", "false"));
    private static final Pattern POSITIVE_INT = Pattern.compile("\\d+");
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*");

    /** Known config keys with their expected types. */
    private static final Map<String, ConfigType> KNOWN_KEYS = new LinkedHashMap<>();
    static {
        KNOWN_KEYS.put("samplingRate", ConfigType.POSITIVE_INT);
        KNOWN_KEYS.put("samplingMaxRate", ConfigType.POSITIVE_INT);
        KNOWN_KEYS.put("samplingThreshold", ConfigType.POSITIVE_LONG);
        KNOWN_KEYS.put("circuitBreakerThreshold", ConfigType.POSITIVE_INT);
        KNOWN_KEYS.put("circuitBreakerCooldownMs", ConfigType.POSITIVE_LONG);
        KNOWN_KEYS.put("metricsPort", ConfigType.PORT);
        KNOWN_KEYS.put("healthPort", ConfigType.PORT);
        KNOWN_KEYS.put("watch", ConfigType.BOOLEAN);
        KNOWN_KEYS.put("jsonEvents", ConfigType.BOOLEAN);
        KNOWN_KEYS.put("disabledPlugins", ConfigType.STRING);
        KNOWN_KEYS.put("config", ConfigType.STRING);
        KNOWN_KEYS.put("otlpEndpoint", ConfigType.STRING);
        KNOWN_KEYS.put("otlpHeaders", ConfigType.STRING);
        KNOWN_KEYS.put("spanExport", ConfigType.BOOLEAN);
        KNOWN_KEYS.put("spanExportBatchSize", ConfigType.POSITIVE_INT);
        KNOWN_KEYS.put("spanExportIntervalMs", ConfigType.POSITIVE_LONG);
        KNOWN_KEYS.put("spanExportBufferSize", ConfigType.POSITIVE_INT);
    }

    private ConfigValidator() {
    }

    /**
     * Validate a configuration map.
     *
     * @param config the configuration to validate
     * @return validation result
     */
    public static ConfigValidationResult validate(Map<String, String> config) {
        if (config == null) {
            return ConfigValidationResult.ok();
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            ConfigType expected = KNOWN_KEYS.get(key);
            if (expected == null) {
                warnings.add("Unknown config key: " + key);
                continue;
            }

            if (value == null || value.trim().isEmpty()) {
                errors.add("Config key '" + key + "' has empty value");
                continue;
            }

            switch (expected) {
                case BOOLEAN:
                    if (!VALID_BOOLEAN_VALUES.contains(value.trim().toLowerCase())) {
                        errors.add("Config '" + key + "' must be true/false, got: " + value);
                    }
                    break;
                case POSITIVE_INT:
                    if (!POSITIVE_INT.matcher(value.trim()).matches()) {
                        errors.add("Config '" + key + "' must be a positive integer, got: " + value);
                    }
                    break;
                case POSITIVE_LONG:
                    try {
                        long v = Long.parseLong(value.trim());
                        if (v <= 0) errors.add("Config '" + key + "' must be > 0, got: " + value);
                    } catch (NumberFormatException e) {
                        errors.add("Config '" + key + "' must be a positive number, got: " + value);
                    }
                    break;
                case PORT:
                    try {
                        int port = Integer.parseInt(value.trim());
                        if (port < 1 || port > 65535) {
                            errors.add("Config '" + key + "' must be 1-65535, got: " + value);
                        }
                    } catch (NumberFormatException e) {
                        errors.add("Config '" + key + "' must be a valid port number, got: " + value);
                    }
                    break;
                case STRING:
                    break; // any value is valid
            }
        }

        return ConfigValidationResult.of(errors, warnings);
    }

    /**
     * Get all known config keys.
     */
    public static Set<String> getKnownKeys() {
        return Collections.unmodifiableSet(KNOWN_KEYS.keySet());
    }

    private enum ConfigType {
        BOOLEAN, POSITIVE_INT, POSITIVE_LONG, PORT, STRING
    }
}
