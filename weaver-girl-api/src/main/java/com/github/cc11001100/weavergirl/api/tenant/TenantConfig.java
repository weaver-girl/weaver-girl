package com.github.cc11001100.weavergirl.api.tenant;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-tenant configuration overrides.
 *
 * <p>Allows different sampling rates, circuit breaker thresholds, etc.
 * for different tenants sharing the same agent instance.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * TenantConfig config = TenantConfig.builder()
 *     .tenantId("premium-customer")
 *     .samplingRate(1)        // 100% sampling for premium
 *     .maxRate(1000)
 *     .customProperty("retentionDays", "30")
 *     .build();
 * TenantConfigRegistry.register(config);
 * </pre>
 *
 * @since 1.1.0
 */
public class TenantConfig {

    private final String tenantId;
    private final int samplingRate;
    private final int maxRate;
    private final long thresholdInvocationsPerSecond;
    private final Map<String, String> customProperties;

    private TenantConfig(Builder builder) {
        this.tenantId = builder.tenantId;
        this.samplingRate = builder.samplingRate;
        this.maxRate = builder.maxRate;
        this.thresholdInvocationsPerSecond = builder.thresholdInvocationsPerSecond;
        this.customProperties = Collections.unmodifiableMap(new HashMap<>(builder.customProperties));
    }

    /** The tenant ID this config applies to. */
    public String getTenantId() { return tenantId; }

    /** Per-tenant sampling rate (1 = sample every request). */
    public int getSamplingRate() { return samplingRate; }

    /** Per-tenant maximum sampling rate. */
    public int getMaxRate() { return maxRate; }

    /** Per-tenant invocation threshold for adaptive sampling. */
    public long getThresholdInvocationsPerSecond() { return thresholdInvocationsPerSecond; }

    /** Custom properties for tenant-specific configuration. */
    public Map<String, String> getCustomProperties() { return customProperties; }

    /**
     * Get a custom property.
     *
     * @param key the property key
     * @return the value, or null if not set
     */
    public String getCustomProperty(String key) {
        return customProperties.get(key);
    }

    /**
     * Get a custom property with a default value.
     *
     * @param key          the property key
     * @param defaultValue the default if not set
     * @return the value, or defaultValue
     */
    public String getCustomProperty(String key, String defaultValue) {
        return customProperties.getOrDefault(key, defaultValue);
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for TenantConfig.
     */
    public static class Builder {
        private String tenantId;
        private int samplingRate = 1;
        private int maxRate = 100;
        private long thresholdInvocationsPerSecond = 10000;
        private final Map<String, String> customProperties = new HashMap<>();

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder samplingRate(int samplingRate) {
            this.samplingRate = samplingRate;
            return this;
        }

        public Builder maxRate(int maxRate) {
            this.maxRate = maxRate;
            return this;
        }

        public Builder thresholdInvocationsPerSecond(long threshold) {
            this.thresholdInvocationsPerSecond = threshold;
            return this;
        }

        public Builder customProperty(String key, String value) {
            this.customProperties.put(key, value);
            return this;
        }

        public TenantConfig build() {
            if (tenantId == null || tenantId.isEmpty()) {
                throw new IllegalArgumentException("tenantId must not be null or empty");
            }
            return new TenantConfig(this);
        }
    }
}
