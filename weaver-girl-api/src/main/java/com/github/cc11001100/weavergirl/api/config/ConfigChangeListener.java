package com.github.cc11001100.weavergirl.api.config;

/**
 * Listener for configuration change events.
 *
 * <p>Register implementations via {@link DynamicConfigManager#addListener}
 * to be notified when configuration values change at runtime.</p>
 *
 * <h3>Example:</h3>
 * <pre>
 * DynamicConfigManager configManager = DynamicConfigManager.getInstance();
 * configManager.addListener("sampling.rate", event -&gt; {
 *     log.info("Sampling rate changed from {} to {}", event.getOldValue(), event.getNewValue());
 * });
 * </pre>
 *
 * <p>Implementations should be thread-safe, as notifications may be
 * delivered from a config watcher thread.</p>
 *
 * @since 1.1.0
 * @see ConfigChangeEvent
 * @see DynamicConfigManager
 */
@FunctionalInterface
public interface ConfigChangeListener {

    /**
     * Called when a configuration value changes.
     *
     * @param event details about the configuration change
     */
    void onConfigChange(ConfigChangeEvent event);
}
