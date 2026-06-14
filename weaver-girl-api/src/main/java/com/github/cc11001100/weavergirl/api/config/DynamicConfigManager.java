package com.github.cc11001100.weavergirl.api.config;

import java.util.List;
import java.util.Map;

/**
 * Central manager for dynamic configuration.
 *
 * <p>Provides a unified API for reading and updating configuration at runtime,
 * with support for:</p>
 * <ul>
 *   <li>Change listeners with key-pattern matching</li>
 *   <li>Multiple config sources with priority</li>
 *   <li>Configuration snapshots and rollback</li>
 *   <li>Audit logging of all changes</li>
 * </ul>
 *
 * <h3>Basic usage:</h3>
 * <pre>
 * DynamicConfigManager config = DynamicConfigManager.getInstance();
 *
 * // Read config
 * String rate = config.get("sampling.rate");
 *
 * // Update config
 * config.set("sampling.rate", "50", "api");
 *
 * // Listen for changes
 * config.addListener(event -&gt; {
 *     log.info("Config changed: {}", event);
 * });
 *
 * // Rollback
 * config.rollback(3); // revert to snapshot version 3
 * </pre>
 *
 * @since 1.1.0
 */
public interface DynamicConfigManager {

    /**
     * Get the singleton instance.
     *
     * @return the global DynamicConfigManager instance
     */
    static DynamicConfigManager getInstance() {
        return DynamicConfigManagerHolder.getInstance();
    }

    // ===== Read operations =====

    /**
     * Get a configuration value.
     *
     * @param key the configuration key
     * @return the value, or null if not set
     */
    String get(String key);

    /**
     * Get a configuration value with a default.
     *
     * @param key          the configuration key
     * @param defaultValue the default if key is not set
     * @return the value, or defaultValue if not set
     */
    String get(String key, String defaultValue);

    /**
     * Get all configuration entries.
     *
     * @return an unmodifiable map of all config key-value pairs
     */
    Map<String, String> getAll();

    // ===== Write operations =====

    /**
     * Set a configuration value.
     *
     * @param key   the configuration key
     * @param value the new value
     * @param source the source making the change (for audit)
     */
    void set(String key, String value, String source);

    /**
     * Remove a configuration key.
     *
     * @param key    the configuration key to remove
     * @param source the source making the change (for audit)
     */
    void remove(String key, String source);

    /**
     * Bulk update configuration values.
     *
     * @param updates the key-value pairs to set
     * @param source  the source making the change
     */
    void setAll(Map<String, String> updates, String source);

    // ===== Listeners =====

    /**
     * Add a listener for all configuration changes.
     *
     * @param listener the listener to add
     */
    void addListener(ConfigChangeListener listener);

    /**
     * Add a listener for changes to a specific key.
     *
     * @param key      the key to watch (exact match)
     * @param listener the listener to add
     */
    void addListener(String key, ConfigChangeListener listener);

    /**
     * Remove a previously added listener.
     *
     * @param listener the listener to remove
     */
    void removeListener(ConfigChangeListener listener);

    /**
     * Get all registered listeners.
     *
     * @return list of registered listeners
     */
    List<ConfigChangeListener> getListeners();

    // ===== Snapshots & Rollback =====

    /**
     * Create a snapshot of the current configuration state.
     *
     * @param description a human-readable description
     * @return the created snapshot
     */
    ConfigSnapshot snapshot(String description);

    /**
     * Get all stored snapshots.
     *
     * @return list of snapshots ordered by version
     */
    List<ConfigSnapshot> getSnapshots();

    /**
     * Rollback to a specific snapshot version.
     *
     * @param version the snapshot version to rollback to
     * @return true if rollback succeeded
     */
    boolean rollback(long version);

    /**
     * Rollback to the previous snapshot.
     *
     * @return true if rollback succeeded, false if no previous snapshot
     */
    boolean rollbackLast();

    // ===== Audit =====

    /**
     * Get the audit log of all configuration changes.
     *
     * @return list of all config change events in chronological order
     */
    List<ConfigChangeEvent> getAuditLog();

    /**
     * Get the audit log for a specific key.
     *
     * @param key the key to filter by
     * @return list of change events for the given key
     */
    List<ConfigChangeEvent> getAuditLog(String key);

    // ===== Lifecycle =====

    /**
     * Load configuration from an external source (e.g. YAML file).
     *
     * @param config the configuration key-value map to load
     * @param source the source identifier
     */
    void loadFromSource(Map<String, String> config, String source);

    /**
     * Shut down the config manager and release resources.
     */
    void shutdown();
}
