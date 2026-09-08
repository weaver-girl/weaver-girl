package com.github.cc11001100.weavergirl.api.context;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * Built-in {@link ContextPropagator} that propagates SLF4J {@code MDC} context across thread
 * boundaries.
 *
 * <p>This propagator captures the MDC context map on the submitting thread and restores it on the
 * worker thread. After the task completes, the worker's prior MDC context is restored
 * symmetrically.
 *
 * <h3>Integration with ThreadContext bridge keys</h3>
 *
 * <p>When {@link TracerPropagator} bridges {@code traceId}/{@code spanId} into ThreadContext and
 * {@link TenantContextPropagator} bridges {@code tenantId} into ThreadContext, this propagator
 * mirrors those values from ThreadContext into MDC during {@link #restore}, so that log statements
 * in the worker thread automatically include trace and tenant information without manual MDC
 * management by the application.
 *
 * <h3>SLF4J availability</h3>
 *
 * <p>The SLF4J API is <strong>not</strong> a compile-time dependency of the {@code weaver-girl-api}
 * module (which stays zero-external-dependency by design). All MDC access is done via reflection,
 * so this propagator works whether or not SLF4J is on the runtime classpath. When SLF4J is absent,
 * capture returns an empty snapshot and restore/cleanup are no-ops.
 *
 * @since 1.8.0
 */
public class MdcPropagator implements ContextPropagator {

  /** Cached reflection state; resolved lazily on first use. */
  private static volatile boolean initialized = false;

  private static volatile boolean slf4jAvailable = false;
  private static volatile Method getCopyOfContextMap;
  private static volatile Method setContextMap;
  private static volatile Method clearMethod;
  private static volatile Method putMethod;

  static final class MdcSnapshot implements Snapshot {
    final Map<String, String> captured;
    static final MdcSnapshot EMPTY = new MdcSnapshot(Collections.<String, String>emptyMap());

    MdcSnapshot(Map<String, String> captured) {
      this.captured = captured;
    }
  }

  @Override
  public String name() {
    return "mdc-context";
  }

  @Override
  public int priority() {
    // After ThreadContext (-200), Tracer (-100), TenantContext (-50)
    // so bridge keys from ThreadContext are available when MDC reads them
    return -40;
  }

  @Override
  public Snapshot capture() {
    Map<String, String> mdc = captureMdc();
    return (mdc == null || mdc.isEmpty()) ? MdcSnapshot.EMPTY : new MdcSnapshot(mdc);
  }

  @Override
  public void restore(Snapshot snapshot) {
    if (!(snapshot instanceof MdcSnapshot)) {
      return;
    }
    MdcSnapshot m = (MdcSnapshot) snapshot;
    if (!m.captured.isEmpty()) {
      // Restore captured MDC map (only when the submitting thread had MDC)
      restoreMdc(m.captured);
    }
    // Always bridge ThreadContext values into MDC. The bridge reads the
    // worker's current ThreadContext, which was populated by
    // TracerPropagator/TenantContextPropagator during their restore()
    // (they ran before us due to lower priority values). This must run
    // even when the captured MDC was empty, so that traceId/spanId/
    // tenantId bridged from other propagators still reach MDC.
    bridgeThreadContextToMdc();
  }

  @Override
  public void cleanup(Snapshot previous) {
    if (!(previous instanceof MdcSnapshot)) {
      return;
    }
    MdcSnapshot p = (MdcSnapshot) previous;
    if (p.captured.isEmpty()) {
      clearMdc();
    } else {
      // Symmetric restore of worker's prior MDC
      clearMdc();
      restoreMdc(p.captured);
    }
  }

  // ---- Reflective SLF4J MDC access ----

  private static void initReflection() {
    if (initialized) {
      return;
    }
    synchronized (MdcPropagator.class) {
      if (initialized) {
        return;
      }
      try {
        Class<?> mdcClass = Class.forName("org.slf4j.MDC");
        getCopyOfContextMap = mdcClass.getMethod("getCopyOfContextMap");
        setContextMap = mdcClass.getMethod("setContextMap", Map.class);
        clearMethod = mdcClass.getMethod("clear");
        putMethod = mdcClass.getMethod("put", String.class, String.class);
        slf4jAvailable = true;
      } catch (Throwable e) {
        // SLF4J not on classpath — propagator becomes a no-op
        slf4jAvailable = false;
      }
      initialized = true;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> captureMdc() {
    initReflection();
    if (!slf4jAvailable) {
      return Collections.emptyMap();
    }
    try {
      Map<String, String> copy = (Map<String, String>) getCopyOfContextMap.invoke(null);
      return copy != null ? copy : Collections.<String, String>emptyMap();
    } catch (Throwable e) {
      return Collections.emptyMap();
    }
  }

  private static void restoreMdc(Map<String, String> map) {
    initReflection();
    if (!slf4jAvailable) {
      return;
    }
    try {
      setContextMap.invoke(null, map);
    } catch (Throwable e) {
      // Unexpected — ignore
    }
  }

  private static void clearMdc() {
    initReflection();
    if (!slf4jAvailable) {
      return;
    }
    try {
      clearMethod.invoke(null);
    } catch (Throwable e) {
      // Unexpected — ignore
    }
  }

  /**
   * Bridge ThreadContext bridge keys into MDC so that log statements automatically include trace
   * and tenant information.
   */
  private static void bridgeThreadContextToMdc() {
    initReflection();
    if (!slf4jAvailable) {
      return;
    }
    try {
      String traceId = ThreadContext.get("traceId");
      if (traceId != null) {
        putMethod.invoke(null, "traceId", traceId);
      }
      String spanId = ThreadContext.get("spanId");
      if (spanId != null) {
        putMethod.invoke(null, "spanId", spanId);
      }
      String tenantId = ThreadContext.get("tenantId");
      if (tenantId != null) {
        putMethod.invoke(null, "tenantId", tenantId);
      }
    } catch (Throwable e) {
      // Unexpected — ignore
    }
  }
}
