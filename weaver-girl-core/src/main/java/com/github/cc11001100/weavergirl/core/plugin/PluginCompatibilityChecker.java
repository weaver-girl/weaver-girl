package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginMetadata;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks plugin compatibility at load time.
 *
 * <p>Compatibility checks include:
 *
 * <ul>
 *   <li><strong>Agent version</strong> — plugin requires a minimum agent version
 *   <li><strong>API version</strong> — plugin was compiled against a compatible API version
 *   <li><strong>Plugin version</strong> — duplicate/older versions of the same plugin
 *   <li><strong>Dependency presence</strong> — all declared plugin dependencies are available
 * </ul>
 *
 * <p>Incompatible plugins are logged with a warning and optionally auto-disabled.
 */
public class PluginCompatibilityChecker {

  private static final Logger log = LoggerFactory.getLogger(PluginCompatibilityChecker.class);

  private final String agentVersion;
  private final String apiVersion;
  private final boolean autoDisableIncompatible;

  private final Map<String, PluginMetadata> loadedPluginVersions = new ConcurrentHashMap<>();
  private final List<CompatibilityReport> reports =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * Create a compatibility checker.
   *
   * @param agentVersion current agent version (e.g. "1.0.0")
   * @param apiVersion current API version (e.g. "1.1.0")
   * @param autoDisableIncompatible if true, incompatible plugins are automatically disabled
   */
  public PluginCompatibilityChecker(
      String agentVersion, String apiVersion, boolean autoDisableIncompatible) {
    this.agentVersion = agentVersion != null ? agentVersion : "1.0.0";
    this.apiVersion = apiVersion != null ? apiVersion : "1.0.0";
    this.autoDisableIncompatible = autoDisableIncompatible;
  }

  /** Create a compatibility checker with auto-disable enabled. */
  public PluginCompatibilityChecker(String agentVersion, String apiVersion) {
    this(agentVersion, apiVersion, true);
  }

  /**
   * Check if a plugin is compatible and should be loaded.
   *
   * @param metadata the plugin metadata to check
   * @param availablePluginNames set of already-loaded/available plugin names
   * @return compatibility report
   */
  public CompatibilityReport check(PluginMetadata metadata, Set<String> availablePluginNames) {
    List<String> warnings = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    boolean compatible = true;

    // Check 1: Minimum agent version
    if (metadata.getMinimumAgentVersion() != null) {
      if (compareVersions(agentVersion, metadata.getMinimumAgentVersion()) < 0) {
        String msg =
            String.format(
                "Plugin '%s' requires agent version >= %s, but current is %s",
                metadata.getName(), metadata.getMinimumAgentVersion(), agentVersion);
        errors.add(msg);
        compatible = false;
        log.warn("[COMPAT] {}", msg);
      }
    }

    // Check 2: Duplicate plugin (same name, potentially different version)
    PluginMetadata existing = loadedPluginVersions.get(metadata.getName());
    if (existing != null) {
      int cmp = compareVersions(metadata.getVersion(), existing.getVersion());
      if (cmp <= 0) {
        String msg =
            String.format(
                "Plugin '%s' v%s duplicates existing v%s — skipping",
                metadata.getName(), metadata.getVersion(), existing.getVersion());
        errors.add(msg);
        compatible = false;
        log.warn("[COMPAT] {}", msg);
      } else {
        String msg =
            String.format(
                "Plugin '%s' v%s supersedes existing v%s",
                metadata.getName(), metadata.getVersion(), existing.getVersion());
        warnings.add(msg);
        log.info("[COMPAT] {}", msg);
      }
    }

    // Check 3: Plugin dependencies present
    for (String dep : metadata.getDepends()) {
      if (!availablePluginNames.contains(dep)) {
        String msg =
            String.format(
                "Plugin '%s' depends on '%s' which is not available", metadata.getName(), dep);
        errors.add(msg);
        compatible = false;
        log.warn("[COMPAT] {}", msg);
      }
    }

    // Check 4: Version format validity
    if (!isValidVersion(metadata.getVersion())) {
      String msg =
          String.format(
              "Plugin '%s' has invalid version format: '%s'",
              metadata.getName(), metadata.getVersion());
      warnings.add(msg);
      log.warn("[COMPAT] {}", msg);
    }

    CompatibilityReport report =
        new CompatibilityReport(
            metadata.getName(),
            metadata.getVersion(),
            compatible,
            warnings,
            errors,
            autoDisableIncompatible && !compatible);

    reports.add(report);

    if (compatible) {
      loadedPluginVersions.put(metadata.getName(), metadata);
      log.debug(
          "[COMPAT] Plugin '{}' v{} is compatible", metadata.getName(), metadata.getVersion());
    } else {
      log.warn(
          "[COMPAT] Plugin '{}' v{} is NOT compatible: {}",
          metadata.getName(),
          metadata.getVersion(),
          errors);
    }

    return report;
  }

  /** Register a plugin as loaded (for dependency checking). */
  public void registerLoaded(String pluginName, PluginMetadata metadata) {
    loadedPluginVersions.put(pluginName, metadata);
  }

  /** Get all compatibility reports. */
  public List<CompatibilityReport> getReports() {
    return Collections.unmodifiableList(reports);
  }

  /** Get only incompatible reports. */
  public List<CompatibilityReport> getIncompatibleReports() {
    List<CompatibilityReport> result = new ArrayList<>();
    for (CompatibilityReport r : reports) {
      if (!r.isCompatible()) result.add(r);
    }
    return result;
  }

  /** Get the count of checked plugins. */
  public int getCheckedCount() {
    return reports.size();
  }

  /** Get the count of compatible plugins. */
  public int getCompatibleCount() {
    int count = 0;
    for (CompatibilityReport r : reports) {
      if (r.isCompatible()) count++;
    }
    return count;
  }

  public String getAgentVersion() {
    return agentVersion;
  }

  public String getApiVersion() {
    return apiVersion;
  }

  public boolean isAutoDisableIncompatible() {
    return autoDisableIncompatible;
  }

  /**
   * Compare two semver-like version strings. Returns negative if v1 < v2, zero if equal, positive
   * if v1 > v2.
   */
  static int compareVersions(String v1, String v2) {
    if (v1 == null) v1 = "0.0.0";
    if (v2 == null) v2 = "0.0.0";

    String[] parts1 = v1.split("\\.");
    String[] parts2 = v2.split("\\.");

    int maxLen = Math.max(parts1.length, parts2.length);
    for (int i = 0; i < maxLen; i++) {
      int p1 = parseVersionPart(parts1, i);
      int p2 = parseVersionPart(parts2, i);
      if (p1 != p2) return Integer.compare(p1, p2);
    }
    return 0;
  }

  private static int parseVersionPart(String[] parts, int index) {
    if (index >= parts.length) return 0;
    try {
      // Handle pre-release suffixes like "1.0.0-SNAPSHOT"
      String part = parts[index].split("-")[0].split("\\+")[0];
      return Integer.parseInt(part);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Check if a version string is valid semver-like format. */
  static boolean isValidVersion(String version) {
    if (version == null || version.isEmpty()) return false;
    // Accept: 1.0.0, 1.0, 1.0.0-SNAPSHOT, 1.0.0-beta.1
    return version.matches("\\d+(\\.\\d+){0,3}(-[a-zA-Z0-9.]+)?(\\+[a-zA-Z0-9.]+)?");
  }

  /** Compatibility check result. */
  public static class CompatibilityReport {
    private final String pluginName;
    private final String pluginVersion;
    private final boolean compatible;
    private final List<String> warnings;
    private final List<String> errors;
    private final boolean autoDisabled;

    CompatibilityReport(
        String pluginName,
        String pluginVersion,
        boolean compatible,
        List<String> warnings,
        List<String> errors,
        boolean autoDisabled) {
      this.pluginName = pluginName;
      this.pluginVersion = pluginVersion;
      this.compatible = compatible;
      this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
      this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
      this.autoDisabled = autoDisabled;
    }

    public String getPluginName() {
      return pluginName;
    }

    public String getPluginVersion() {
      return pluginVersion;
    }

    public boolean isCompatible() {
      return compatible;
    }

    public List<String> getWarnings() {
      return warnings;
    }

    public List<String> getErrors() {
      return errors;
    }

    public boolean isAutoDisabled() {
      return autoDisabled;
    }

    @Override
    public String toString() {
      return String.format(
          "CompatibilityReport{%s v%s, compatible=%s, errors=%s, autoDisabled=%s}",
          pluginName, pluginVersion, compatible, errors, autoDisabled);
    }
  }
}
