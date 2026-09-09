package com.github.cc11001100.weavergirl.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

/**
 * Automatic MDC injection for intercepted methods. Bridges trace/span/tenant context from
 * ThreadContext into SLF4J MDC so that application logs automatically include correlation fields
 * without manual MDC management.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * MdcSnapshot before = MdcInjector.inject();
 * try {
 *     // instrumented method executes with traceId/spanId/tenantId in MDC
 * } finally {
 *     MdcInjector.restore(before);
 * }
 * }</pre>
 *
 * <p>This is automatically invoked by {@link com.github.cc11001100.weavergirl.core.InterceptAdvice}
 * for every intercepted method when SLF4J is available. When SLF4J is absent, operations are no-ops.
 *
 * @since 2.0.0
 */
public final class MdcInjector {

  private static final String[] BRIDGE_KEYS = {"traceId", "spanId", "tenantId"};

  private MdcInjector() {}

  /** Snapshot of the MDC state before injection. */
  public static final class MdcSnapshot {
    private final Map<String, String> previous;
    private MdcSnapshot(Map<String, String> previous) {
      this.previous = previous;
    }
    public Map<String, String> getPrevious() {
      return previous;
    }
  }

  /**
   * Capture current MDC, inject ThreadContext bridge keys into MDC, and return the previous MDC
   * snapshot for later restoration.
   *
   * <p>This applies a clean bridge: only bridge keys present in ThreadContext with non-empty values
   * are added to MDC; bridge keys absent from ThreadContext are removed from MDC so stale values
   * from prior injections do not leak.
   */
  public static MdcSnapshot inject() {
    Map<String, String> previous;
    try {
      previous = MDC.getCopyOfContextMap();
    } catch (Throwable e) {
      previous = null;
    }
    if (previous == null) {
      previous = Collections.emptyMap();
    }

    try {
      Map<String, String> current;
      try {
        current = MDC.getCopyOfContextMap();
      } catch (Throwable e) {
        current = null;
      }
      if (current == null) {
        current = new HashMap<>();
      }

      // Apply bridge keys from ThreadContext; remove bridge keys not present in ThreadContext.
      boolean changed = false;
      for (String key : BRIDGE_KEYS) {
        String value = com.github.cc11001100.weavergirl.api.context.ThreadContext.get(key);
        if (value != null && !value.isEmpty()) {
          if (!value.equals(current.get(key))) {
            current.put(key, value);
            changed = true;
          }
        } else {
          if (current.containsKey(key)) {
            current.remove(key);
            changed = true;
          }
        }
      }
      if (changed) {
        MDC.setContextMap(current);
      }
    } catch (Throwable e) {
      // If MDC operations fail, continue without breaking the intercepted method
    }

    return new MdcSnapshot(previous);
  }

  /**
   * Restore MDC to the state captured by {@link #inject()}. This replaces the current MDC state
   * with the exact state that existed before injection, including any bridge keys that were
   * present at that time.
   */
  public static void restore(MdcSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    try {
      MDC.setContextMap(snapshot.getPrevious());
    } catch (Throwable e) {
      // Never let MDC cleanup break the intercepted method
    }
  }

  /** Check whether SLF4J MDC is available and functional at runtime. */
  public static boolean isMdcAvailable() {
    try {
      Class<?> mdcClass = Class.forName("org.slf4j.MDC");
      if (!mdcClass.isAssignableFrom(org.slf4j.MDC.class)) {
        return false;
      }
      // Prove actual MDC functionality rather than just class presence.
      // Some bindings (e.g. slf4j-nop) provide an MDC class but never store values.
      // Avoid SamplingController/shouldSample(): that path increments an atomic counter
      // and would mutate global sampling state just for a probe.
      MDC.put("_weaver_mdc_probe", "probe");
      String value = MDC.get("_weaver_mdc_probe");
      MDC.remove("_weaver_mdc_probe");
      return "probe".equals(value);
    } catch (Throwable e) {
      return false;
    }
  }
}
