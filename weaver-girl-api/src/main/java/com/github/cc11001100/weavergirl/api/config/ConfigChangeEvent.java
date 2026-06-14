package com.github.cc11001100.weavergirl.api.config;

/**
 * Event fired when a configuration value changes.
 *
 * <p>Contains the key, old value, new value, and the source that triggered
 * the change. Listeners can use this to react to dynamic configuration
 * updates at runtime.</p>
 *
 * <h3>Example usage:</h3>
 * <pre>
 * configManager.addListener("sampling.rate", event -> {
 *     int newRate = Integer.parseInt(event.getNewValue());
 *     samplingController.setSamplingRate(newRate);
 * });
 * </pre>
 *
 * @since 1.1.0
 */
public class ConfigChangeEvent {

    private final String key;
    private final String oldValue;
    private final String newValue;
    private final String source;
    private final long timestamp;

    /**
     * Construct a config change event.
     *
     * @param key       the configuration key that changed
     * @param oldValue  the previous value (may be null if key was unset)
     * @param newValue  the new value (may be null if key was removed)
     * @param source    the source that triggered the change (e.g. "yaml", "system-property", "api")
     */
    public ConfigChangeEvent(String key, String oldValue, String newValue, String source) {
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }

    /** The configuration key that changed. */
    public String getKey() {
        return key;
    }

    /** The previous value, or null if the key was not previously set. */
    public String getOldValue() {
        return oldValue;
    }

    /** The new value, or null if the key was removed. */
    public String getNewValue() {
        return newValue;
    }

    /** The source that triggered this change (e.g. "yaml", "system-property", "api"). */
    public String getSource() {
        return source;
    }

    /** The timestamp (epoch millis) when this event was created. */
    public long getTimestamp() {
        return timestamp;
    }

    /** Whether this is a key removal (newValue is null). */
    public boolean isRemoval() {
        return newValue == null;
    }

    /** Whether this is a new key creation (oldValue is null). */
    public boolean isCreation() {
        return oldValue == null && newValue != null;
    }

    @Override
    public String toString() {
        return "ConfigChangeEvent{key='" + key + "', old='" + oldValue
                + "', new='" + newValue + "', source='" + source + "'}";
    }
}
