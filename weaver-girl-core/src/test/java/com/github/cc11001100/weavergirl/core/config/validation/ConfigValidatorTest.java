package com.github.cc11001100.weavergirl.core.config.validation;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for config validation (P63).
 */
class ConfigValidatorTest {

    @Test
    void validConfig_passes() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("samplingRate", "10");
        config.put("watch", "true");
        config.put("metricsPort", "9400");
        config.put("jsonEvents", "false");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void invalidBoolean_fails() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("watch", "yes");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("must be true/false"));
    }

    @Test
    void invalidPort_fails() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("metricsPort", "99999");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("1-65535"));
    }

    @Test
    void negativeValue_fails() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("samplingRate", "-1");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("positive"));
    }

    @Test
    void emptyValue_fails() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("samplingRate", "");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("empty"));
    }

    @Test
    void unknownKey_warns() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("unknownKey123", "value");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.isValid()); // warnings don't block
        assertTrue(result.hasWarnings());
        assertTrue(result.getWarnings().get(0).contains("Unknown"));
    }

    @Test
    void nullConfig_passes() {
        ConfigValidationResult result = ConfigValidator.validate(null);
        assertTrue(result.isValid());
    }

    @Test
    void emptyConfig_passes() {
        ConfigValidationResult result = ConfigValidator.validate(new HashMap<>());
        assertTrue(result.isValid());
    }

    @Test
    void nonNumericPort_fails() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("metricsPort", "abc");
        ConfigValidationResult result = ConfigValidator.validate(config);
        assertFalse(result.isValid());
    }

    @Test
    void result_toString_valid() {
        ConfigValidationResult result = ConfigValidationResult.ok();
        assertTrue(result.toString().contains("valid"));
    }

    @Test
    void result_toString_withErrors() {
        ConfigValidationResult result = ConfigValidationResult.error("bad config");
        assertTrue(result.toString().contains("ERRORS"));
    }

    @Test
    void spanExportKeys_recognized() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("otlpEndpoint", "http://localhost:4318/v1/traces");
        config.put("otlpHeaders", "Authorization=Bearer token");
        config.put("spanExport", "true");
        config.put("spanExportBatchSize", "100");
        config.put("spanExportIntervalMs", "5000");
        config.put("spanExportBufferSize", "10000");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void invalidSpanExportValues_fail() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("spanExport", "yes");
        config.put("spanExportBatchSize", "-5");

        ConfigValidationResult result = ConfigValidator.validate(config);
        assertFalse(result.isValid());
        assertEquals(2, result.getErrors().size());
    }

    @Test
    void getKnownKeys_notEmpty() {
        assertFalse(ConfigValidator.getKnownKeys().isEmpty());
        assertTrue(ConfigValidator.getKnownKeys().contains("samplingRate"));
    }
}
