package com.github.cc11001100.weavergirl.api.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of plugin-contributed {@link HealthCheck}s and {@link HealthCheckExtension}s.
 *
 * <p>Health is a fourth signal next to spans, metrics and events: probes and the Prometheus exporter
 * both read this registry, so one check serves every consumer. Evaluation is on demand — nothing
 * runs in the background.
 *
 * <p>Two shapes are accepted and evaluated identically:
 *
 * <ul>
 *   <li>a {@link HealthCheck} — a standalone probe with its own {@code name()} and {@code check()};
 *   <li>a {@link HealthCheckExtension} — an extension that knows how to check itself, registered as
 *       a capability of that extension rather than as a separate probe object.
 * </ul>
 *
 * <p>Each check is isolated: one that throws (or returns null) is reported as {@code DOWN} and the
 * others still run. Registration order is evaluation order, and the first check that claims a name
 * owns it; a later duplicate name is registered but never evaluated, so unloading the original
 * brings the duplicate into play rather than silently changing who answers. Standalone checks are
 * evaluated before extension checks.
 *
 * <p>Every evaluation is timed and counted in {@link ExtensionPerformanceTracker} under {@link
 * ExtensionPerformanceTracker#KIND_HEALTH_CHECK}, so a slow or persistently failing check shows up
 * in a scrape instead of only in the status it reports.
 *
 * <p>Register through {@code PluginContext.registerHealthCheck(...)} or {@code
 * PluginContext.registerHealthCheckExtension(...)} so the engine withdraws the check when the plugin
 * is unloaded.
 *
 * @since 1.0.0
 */
public final class HealthChecks {

  private static final List<Holder> holders = new CopyOnWriteArrayList<Holder>();
  private static final List<ExtensionHolder> extensionHolders =
      new CopyOnWriteArrayList<ExtensionHolder>();

  private HealthChecks() {}

  /**
   * Register a check. Registering the same instance twice is a no-op.
   *
   * @param check the check to add
   */
  public static void register(HealthCheck check) {
    if (check == null) {
      return;
    }
    synchronized (HealthChecks.class) {
      for (Holder holder : holders) {
        if (holder.check == check) {
          return;
        }
      }
      holders.add(new Holder(check, nameOf(check)));
    }
  }

  /**
   * Register an extension's self-reported health check. Registering the same extension twice is a
   * no-op; the component name is the extension's {@link HealthCheckExtension#name()}.
   *
   * @param extension the extension to monitor
   */
  public static void register(HealthCheckExtension extension) {
    register(null, extension);
  }

  /**
   * Register an extension's self-reported health check under a qualified component name.
   *
   * <p>Used by the engine to prefix the owning plugin name, so two plugins reporting the same
   * component name stay distinct in the health report and in the metrics series.
   *
   * @param namePrefix owner name to prefix, or null to use the extension name as-is
   * @param extension the extension to monitor
   */
  public static void register(String namePrefix, HealthCheckExtension extension) {
    if (extension == null) {
      return;
    }
    synchronized (HealthChecks.class) {
      for (ExtensionHolder holder : extensionHolders) {
        if (holder.extension == extension) {
          return;
        }
      }
      extensionHolders.add(new ExtensionHolder(extension, componentName(namePrefix, extension)));
    }
  }

  /**
   * Remove a check.
   *
   * @param check the check to remove
   */
  public static void unregister(HealthCheck check) {
    if (check == null) {
      return;
    }
    synchronized (HealthChecks.class) {
      for (Holder holder : holders) {
        if (holder.check == check) {
          holders.remove(holder);
          return;
        }
      }
    }
  }

  /**
   * Remove an extension's health check.
   *
   * @param extension the extension to stop monitoring
   */
  public static void unregister(HealthCheckExtension extension) {
    if (extension == null) {
      return;
    }
    synchronized (HealthChecks.class) {
      for (ExtensionHolder holder : extensionHolders) {
        if (holder.extension == extension) {
          extensionHolders.remove(holder);
          return;
        }
      }
    }
  }

  /** The registered standalone checks, in evaluation order. */
  public static List<HealthCheck> getChecks() {
    List<HealthCheck> checks = new ArrayList<HealthCheck>(holders.size());
    for (Holder holder : holders) {
      checks.add(holder.check);
    }
    return Collections.unmodifiableList(checks);
  }

  /** The extensions being monitored, in evaluation order. */
  public static List<HealthCheckExtension> getExtensions() {
    List<HealthCheckExtension> extensions = new ArrayList<HealthCheckExtension>(extensionHolders.size());
    for (ExtensionHolder holder : extensionHolders) {
      extensions.add(holder.extension);
    }
    return Collections.unmodifiableList(extensions);
  }

  /** Whether nothing at all is registered, checks and extensions alike. */
  public static boolean isEmpty() {
    return holders.isEmpty() && extensionHolders.isEmpty();
  }

  /**
   * Evaluate every registered check once.
   *
   * <p>Runs on the caller's thread; a slow check delays the caller (probe or scrape), so checks
   * must be cheap. Exceptions and null results degrade to {@code DOWN} and are isolated.
   *
   * @return an immutable report, never null
   */
  public static HealthReport checkAll() {
    long startMs = System.currentTimeMillis();
    Map<String, HealthCheckResult> components = new LinkedHashMap<String, HealthCheckResult>();
    for (Holder holder : holders) {
      if (components.containsKey(holder.name)) {
        continue;
      }
      components.put(holder.name, holder.evaluate());
    }
    for (ExtensionHolder holder : extensionHolders) {
      if (components.containsKey(holder.name)) {
        continue;
      }
      components.put(holder.name, holder.evaluate());
    }
    long durationMs = System.currentTimeMillis() - startMs;
    return new HealthReport(aggregate(components), components, durationMs, System.currentTimeMillis());
  }

  /** Remove every check and extension. Tests use this so one test cannot see another's registry. */
  public static void clear() {
    synchronized (HealthChecks.class) {
      holders.clear();
      extensionHolders.clear();
    }
  }

  private static HealthCheckResult.Status aggregate(Map<String, HealthCheckResult> components) {
    if (components.isEmpty()) {
      return HealthCheckResult.Status.UP;
    }
    boolean degraded = false;
    boolean unknown = false;
    for (HealthCheckResult result : components.values()) {
      if (result.getStatus() == HealthCheckResult.Status.DOWN) {
        return HealthCheckResult.Status.DOWN;
      }
      degraded |= result.getStatus() == HealthCheckResult.Status.DEGRADED;
      unknown |= result.getStatus() == HealthCheckResult.Status.UNKNOWN;
    }
    if (degraded) {
      return HealthCheckResult.Status.DEGRADED;
    }
    return unknown ? HealthCheckResult.Status.UNKNOWN : HealthCheckResult.Status.UP;
  }

  private static String nameOf(HealthCheck check) {
    String name;
    try {
      name = check.name();
    } catch (RuntimeException e) {
      name = null;
    }
    if (name == null || name.trim().isEmpty()) {
      return check.getClass().getName();
    }
    return name.trim();
  }

  /**
   * The component name an extension is reported under: {@code namePrefix + "/" + extension.name()},
   * or the extension name alone when no prefix is given. Exposed so the owner (the engine) can
   * address the same series when it withdraws the check.
   *
   * @param namePrefix owner name to prefix, or null
   * @param extension the extension
   * @return the component name, never null
   */
  public static String componentName(String namePrefix, HealthCheckExtension extension) {
    String name;
    try {
      name = extension.name();
    } catch (RuntimeException e) {
      name = null;
    }
    if (name == null || name.trim().isEmpty()) {
      name = extension.getClass().getName();
    } else {
      name = name.trim();
    }
    if (namePrefix == null || namePrefix.trim().isEmpty()) {
      return name;
    }
    return namePrefix.trim() + "/" + name;
  }

  static HealthCheckResult downOnThrow(RuntimeException e) {
    Map<String, String> details = new LinkedHashMap<String, String>();
    details.put("error", e.getClass().getName());
    if (e.getMessage() != null) {
      details.put("message", e.getMessage());
    }
    return HealthCheckResult.down(details);
  }

  /** A standalone check plus the name captured at registration, so evaluation cannot be steered later. */
  private static final class Holder {
    final HealthCheck check;
    final String name;

    Holder(HealthCheck check, String name) {
      this.check = check;
      this.name = name;
    }

    HealthCheckResult evaluate() {
      long start = System.nanoTime();
      try {
        HealthCheckResult result = check.check();
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK,
            name,
            System.nanoTime() - start,
            false);
        if (result == null) {
          return HealthCheckResult.down("check returned null");
        }
        return result;
      } catch (RuntimeException e) {
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK,
            name,
            System.nanoTime() - start,
            true);
        return downOnThrow(e);
      }
    }
  }

  /**
   * An extension monitored through {@link HealthCheckExtension#checkHealth()}, adapted to the same
   * isolation and accounting as a standalone check.
   */
  private static final class ExtensionHolder {
    final HealthCheckExtension extension;
    final String name;

    ExtensionHolder(HealthCheckExtension extension, String name) {
      this.extension = extension;
      this.name = name;
    }

    HealthCheckResult evaluate() {
      long start = System.nanoTime();
      try {
        HealthCheckResult result = extension.checkHealth();
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK,
            name,
            System.nanoTime() - start,
            false);
        if (result == null) {
          return HealthCheckResult.down("extension returned null health status");
        }
        return result;
      } catch (RuntimeException e) {
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK,
            name,
            System.nanoTime() - start,
            true);
        return downOnThrow(e);
      }
    }
  }
}
