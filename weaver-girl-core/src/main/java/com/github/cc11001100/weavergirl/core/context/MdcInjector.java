package com.github.cc11001100.weavergirl.core.context;

import org.slf4j.MDC;

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

  /**
   * Shared snapshot for "nothing was written": either the thread carried no bridge values that
   * differed from MDC, or the MDC threw and no write was attempted. Restoring it is a no-op.
   *
   * <p>Returning this instead of a fresh snapshot keeps the common case (no trace context on the
   * thread) free of per-call allocation.
   */
  private static final MdcSnapshot EMPTY_SNAPSHOT = MdcSnapshot.none();

  private MdcInjector() {}

  /**
   * The previous values of the bridge keys, one entry per {@link #BRIDGE_KEYS} slot; {@code null}
   * means the key was absent, and {@code -1} in {@link #changed} means its injection was not
   * attempted.
   *
   * <p>Only the bridge keys are captured. Restoring them leaves every other MDC entry — including
   * entries the intercepted method added — untouched. That is both cheaper (no copy of the whole
   * context map per call) and less surprising than replacing the entire MDC.
   *
   * <p>{@link #changed} records which keys were actually written, so restore puts back only those
   * (or, on the way, skips them if the body already overwrote the value with a newer one).
   */
  public static final class MdcSnapshot {
    private final String[] previous;
    /** Bit i set when BRIDGE_KEYS[i] was written by the injection. 0 means nothing was written. */
    private final int written;

    private static final int NONE_WRITTEN = 0;

    private MdcSnapshot(String[] previous, int written) {
      this.previous = previous;
      this.written = written;
    }

    private static MdcSnapshot none() {
      return new MdcSnapshot(new String[0], NONE_WRITTEN);
    }
  }

  /**
   * Capture the current bridge keys, inject the ThreadContext values into MDC, and return the
   * previous bridge-key values for later restoration.
   *
   * <p>This applies a clean bridge: only bridge keys present in ThreadContext with non-empty values
   * are added to MDC; bridge keys absent from ThreadContext are removed from MDC so stale values
   * from prior injections do not leak.
   *
   * <p>The switch is checked here as well as in {@link #isMdcAvailable()}: this method is public
   * and callers other than the advice use it, so "the bridge is off" has to mean no writes, not
   * only "the advice does not ask".
   */
  public static MdcSnapshot inject() {
    if (!com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.isMdcBridgeEnabled()) {
      return EMPTY_SNAPSHOT;
    }
    // Read and compare the three keys first. On the common path — no tracing context on the thread
    // and no stale bridge key in MDC — nothing needs to be written, and the answer is the shared
    // EMPTY_SNAPSHOT: no array, no snapshot object, nothing for restore to undo. That keeps the
    // per-call MDC bridge allocation-free. A failure while reading means the MDC is unusable: also
    // return EMPTY_SNAPSHOT so the caller's restore is a no-op and no write is ever attempted.
    String current0;
    String current1;
    String current2;
    String desired0;
    String desired1;
    String desired2;
    try {
      current0 = MDC.get(BRIDGE_KEYS[0]);
      current1 = MDC.get(BRIDGE_KEYS[1]);
      current2 = MDC.get(BRIDGE_KEYS[2]);
      desired0 = bridgeValue(BRIDGE_KEYS[0]);
      desired1 = bridgeValue(BRIDGE_KEYS[1]);
      desired2 = bridgeValue(BRIDGE_KEYS[2]);
    } catch (Throwable e) {
      return EMPTY_SNAPSHOT;
    }
    // Only the keys that are actually different get written. In the tracing case that is usually
    // one key (traceId) rather than three puts plus three removes per call.
    int written = 0;
    if (differs(desired0, current0)) {
      written |= 1;
    }
    if (differs(desired1, current1)) {
      written |= 2;
    }
    if (differs(desired2, current2)) {
      written |= 4;
    }
    if (written == 0) {
      return EMPTY_SNAPSHOT;
    }

    MdcSnapshot snapshot = new MdcSnapshot(new String[] {current0, current1, current2}, written);
    try {
      if ((written & 1) != 0) {
        apply(BRIDGE_KEYS[0], desired0);
      }
      if ((written & 2) != 0) {
        apply(BRIDGE_KEYS[1], desired1);
      }
      if ((written & 4) != 0) {
        apply(BRIDGE_KEYS[2], desired2);
      }
    } catch (Throwable e) {
      // If MDC operations fail, continue without breaking the intercepted method
    }
    return snapshot;
  }

  private static boolean differs(String desired, String current) {
    return desired == null ? current != null : !desired.equals(current);
  }

  private static void apply(String key, String value) {
    if (value != null) {
      MDC.put(key, value);
    } else {
      MDC.remove(key);
    }
  }

  /** The bridge value for {@code key}, with an empty string treated as absent. */
  private static String bridgeValue(String key) {
    String value = com.github.cc11001100.weavergirl.api.context.ThreadContext.get(key);
    return value == null || value.isEmpty() ? null : value;
  }

  /**
   * Restore the bridge keys to the values captured by {@link #inject()}. Other MDC entries are
   * left as they are.
   *
   * <p>Only the keys the injection actually wrote are touched, and a key whose current value no
   * longer matches what was injected is left alone: the intercepted method (or a hook) changed it
   * deliberately, and undoing that would be worse than leaving it.
   */
  public static void restore(MdcSnapshot snapshot) {
    if (snapshot == null || snapshot.written == 0) {
      return;
    }
    String[] previous = snapshot.previous;
    for (int i = 0; i < BRIDGE_KEYS.length; i++) {
      if ((snapshot.written & (1 << i)) == 0) {
        continue;
      }
      try {
        restoreOne(BRIDGE_KEYS[i], previous[i]);
      } catch (Throwable e) {
        // Never let MDC cleanup break the intercepted method
      }
    }
  }

  private static void restoreOne(String key, String previousValue) {
    if (previousValue != null) {
      MDC.put(key, previousValue);
    } else {
      MDC.remove(key);
    }
  }

  /**
   * Cached result of the MDC probe. The probe loads a class, writes, reads and removes an MDC
   * entry — roughly 160 ns — and the advice calls it on every intercepted invocation, so the
   * answer is computed once. A binding cannot change after startup, so caching cannot go stale in
   * a way that matters.
   */
  private static volatile Boolean mdcAvailable;

  /**
   * Whether the bridge should run: it is switched on ({@code -Dweavergirl.mdc.enabled},
   * {@code mdc=false}, or a YAML {@code mdc} key) and SLF4J MDC is present and functional.
   *
   * <p>The switch check comes first so a deployment that turned the bridge off does not even pay
   * the cached-probe read.
   */
  public static boolean isMdcAvailable() {
    if (!com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.isMdcBridgeEnabled()) {
      return false;
    }
    Boolean cached = mdcAvailable;
    if (cached != null) {
      return cached;
    }
    boolean available = probeMdc();
    mdcAvailable = available;
    return available;
  }

  private static boolean probeMdc() {
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

  /** Test hook: forget the cached probe result. */
  static void resetMdcProbeForTest() {
    mdcAvailable = null;
  }
}
