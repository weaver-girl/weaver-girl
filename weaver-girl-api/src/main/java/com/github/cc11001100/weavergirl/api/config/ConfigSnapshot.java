package com.github.cc11001100.weavergirl.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable snapshot of configuration state at a point in time.
 *
 * <p>Used for rollback support — the {@link DynamicConfigManager} stores snapshots that can be
 * restored to revert configuration changes.
 *
 * @since 1.1.0
 */
public class ConfigSnapshot {

  private final long version;
  private final long timestamp;
  private final Map<String, String> config;
  private final String description;

  /**
   * Create a configuration snapshot.
   *
   * @param version the snapshot version number
   * @param timestamp the creation timestamp (epoch millis)
   * @param config the configuration key-value map (will be copied)
   * @param description a human-readable description of this snapshot
   */
  public ConfigSnapshot(
      long version, long timestamp, Map<String, String> config, String description) {
    this.version = version;
    this.timestamp = timestamp;
    this.config = Collections.unmodifiableMap(new LinkedHashMap<>(config));
    this.description = description;
  }

  /** The snapshot version number (monotonically increasing). */
  public long getVersion() {
    return version;
  }

  /** The timestamp when this snapshot was taken. */
  public long getTimestamp() {
    return timestamp;
  }

  /** The configuration key-value map at the time of this snapshot. */
  public Map<String, String> getConfig() {
    return config;
  }

  /** A human-readable description of this snapshot. */
  public String getDescription() {
    return description;
  }

  /** Get a specific config value from this snapshot. */
  public String get(String key) {
    return config.get(key);
  }

  /** The number of configuration entries in this snapshot. */
  public int size() {
    return config.size();
  }

  @Override
  public String toString() {
    return "ConfigSnapshot{version="
        + version
        + ", entries="
        + config.size()
        + ", desc='"
        + description
        + "'}";
  }
}
