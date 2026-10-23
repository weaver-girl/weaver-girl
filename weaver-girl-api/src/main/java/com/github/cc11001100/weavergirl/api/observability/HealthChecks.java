package com.github.cc11001100.weavergirl.api.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of plugin-contributed {@link HealthCheck}s.
 *
 * <p>Health is a fourth signal next to spans, metrics and events: probes and the Prometheus exporter
 * both read this registry, so one check serves every consumer. Evaluation is on demand — nothing
 * runs in the background.
 *
 * <p>Each check is isolated: one that throws (or returns null) is reported as {@code DOWN} and the
 * others still run. Registration order is evaluation order, and the first check that claims a name
 * owns it; a later duplicate name is registered but never evaluated, so unloading the original
 * brings the duplicate into play rather than silently changing who answers.
 *
 * <p>Register through {@code PluginContext.registerHealthCheck(...)} so the engine withdraws the
 * check when the plugin is unloaded.
 *
 * @since 1.0.0
 */
public final class HealthChecks {

  private static final List<Holder> holders = new CopyOnWriteArrayList<Holder>();

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

  /** The registered checks, in evaluation order. */
  public static List<HealthCheck> getChecks() {
    List<HealthCheck> checks = new ArrayList<HealthCheck>(holders.size());
    for (Holder holder : holders) {
      checks.add(holder.check);
    }
    return Collections.unmodifiableList(checks);
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
    long durationMs = System.currentTimeMillis() - startMs;
    return new HealthReport(aggregate(components), components, durationMs, System.currentTimeMillis());
  }

  /** Remove every check. Tests use this so one test cannot see another's registry. */
  public static void clear() {
    synchronized (HealthChecks.class) {
      holders.clear();
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

  /** A check plus the name captured at registration, so evaluation cannot be steered later. */
  private static final class Holder {
    final HealthCheck check;
    final String name;

    Holder(HealthCheck check, String name) {
      this.check = check;
      this.name = name;
    }

    HealthCheckResult evaluate() {
      try {
        HealthCheckResult result = check.check();
        if (result == null) {
          return HealthCheckResult.down("check returned null");
        }
        return result;
      } catch (RuntimeException e) {
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("error", e.getClass().getName());
        if (e.getMessage() != null) {
          details.put("message", e.getMessage());
        }
        return HealthCheckResult.down(details);
      }
    }
  }
}
