package com.github.cc11001100.weavergirl.plugins.logging;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Injects the active trace context into whatever the logging backend actually reads, resolved
 * reflectively so the plugin carries no dependency on either backend.
 *
 * <p>Two backends matter, and they read different storage:
 *
 * <ul>
 *   <li><b>SLF4J MDC</b> (Logback, log4j-slf4j2-impl, …) — {@code org.slf4j.MDC}. Present whenever
 *       slf4j-api is on the application classpath.
 *   <li><b>Log4j2 ThreadContext</b> — {@code org.apache.logging.log4j.ThreadContext}, which is
 *       <em>not</em> backed by the SLF4J MDC unless log4j-slf4j2-impl is the binding. A Log4j2
 *       application that logs through the Log4j2 API directly sees only ThreadContext.
 * </ul>
 *
 * <p>Neither is a compile dependency: the methods are looked up once, at class-initialisation time.
 * When the backend is absent, the corresponding accessor is null and every operation on it is a
 * no-op, so the plugin loads and unloads cleanly on an application with no logging backend at all.
 *
 * <p>One instance is created per plugin instance and discarded with it — there is no static cache
 * keyed by class loader, which is what would otherwise pin a backend jar after an unload.
 *
 * @since 1.0.0
 */
final class LogCorrelation {

  /** Keys always written when present in the context, independent of {@code mdcKeys}. */
  static final String TRACE_ID_KEY = "traceId";

  /** @see #TRACE_ID_KEY */
  static final String SPAN_ID_KEY = "spanId";

  private final Method slf4jPut;
  private final Method slf4jRemove;

  private final Method log4j2Put;
  private final Method log4j2Remove;

  LogCorrelation() {
    this(LogCorrelation.class.getClassLoader());
  }

  /**
   * @param loader the loader to resolve the backends through — the plugin's own, so the same
   *     chain the agent loaded the plugin from
   */
  LogCorrelation(ClassLoader loader) {
    Method put = null;
    Method remove = null;
    try {
      Class<?> mdc = Class.forName("org.slf4j.MDC", true, loader);
      put = mdc.getMethod("put", String.class, String.class);
      remove = mdc.getMethod("remove", String.class);
    } catch (Throwable ignored) {
      put = null;
      remove = null;
    }
    this.slf4jPut = put;
    this.slf4jRemove = remove;

    Method threadContextPut = null;
    Method threadContextRemove = null;
    try {
      Class<?> threadContext =
          Class.forName("org.apache.logging.log4j.ThreadContext", true, loader);
      threadContextPut = threadContext.getMethod("put", String.class, String.class);
      threadContextRemove = threadContext.getMethod("remove", String.class);
    } catch (Throwable ignored) {
      threadContextPut = null;
      threadContextRemove = null;
    }
    this.log4j2Put = threadContextPut;
    this.log4j2Remove = threadContextRemove;
  }

  /** Whether SLF4J MDC is present and usable. */
  boolean slf4jAvailable() {
    return slf4jPut != null;
  }

  /** Whether Log4j2's {@code ThreadContext} is present and usable. */
  boolean log4j2Available() {
    return log4j2Put != null;
  }

  /** Whether at least one backend is present; when false, nothing is worth weaving. */
  boolean anyBackendAvailable() {
    return slf4jAvailable() || log4j2Available();
  }

  /**
   * Writes {@code entries} into every available backend, returning what was there before so {@link
   * #restore(Map)} can put it back.
   *
   * <p>The snapshot is taken per key and per backend: a key that had no value is recorded as
   * {@code null} and removed on restore, so this never leaks a trace id into the next call on the
   * same thread.
   *
   * @param entries key to value; entries with a null or empty value are skipped
   * @return the previous values, keyed by backends-prefixed key, or an empty map when there is
   *     nothing to restore
   */
  Map<String, String> inject(Map<String, String> entries) {
    if (entries == null || entries.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    Map<String, String> previous = new HashMap<String, String>(entries.size() * 2);
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
        continue;
      }
      if (slf4jAvailable()) {
        previous.put(slf4jKey(key), getSlf4j(key));
        invoke(slf4jPut, key, value);
      }
      if (log4j2Available()) {
        previous.put(log4j2Key(key), getLog4j2(key));
        invoke(log4j2Put, key, value);
      }
    }
    return previous;
  }

  /**
   * Restores the state captured by {@link #inject(Map)}: a key that had a value gets it back, a key
   * that had none is removed.
   */
  void restore(Map<String, String> previous) {
    if (previous == null || previous.isEmpty()) {
      return;
    }
    for (Map.Entry<String, String> entry : previous.entrySet()) {
      String stored = entry.getKey();
      String value = entry.getValue();
      if (stored.startsWith("slf4j:")) {
        String key = stored.substring("slf4j:".length());
        if (value == null) {
          invoke(slf4jRemove, key);
        } else {
          invoke(slf4jPut, key, value);
        }
      } else if (stored.startsWith("log4j2:")) {
        String key = stored.substring("log4j2:".length());
        if (value == null) {
          invoke(log4j2Remove, key);
        } else {
          invoke(log4j2Put, key, value);
        }
      }
    }
  }

  private static String slf4jKey(String key) {
    return "slf4j:" + key;
  }

  private static String log4j2Key(String key) {
    return "log4j2:" + key;
  }

  private String getSlf4j(String key) {
    // Read back through getCopyOfContextMap-free API: MDC.get is the only accessor that does not
    // allocate the whole map. Resolve it lazily so a binding without it degrades to "no previous".
    try {
      Method get = Class.forName("org.slf4j.MDC", false, LogCorrelation.class.getClassLoader())
          .getMethod("get", String.class);
      Object value = get.invoke(null, key);
      return value == null ? null : value.toString();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private String getLog4j2(String key) {
    try {
      Method get =
          Class.forName(
                  "org.apache.logging.log4j.ThreadContext",
                  false,
                  LogCorrelation.class.getClassLoader())
              .getMethod("get", String.class);
      Object value = get.invoke(null, key);
      return value == null ? null : value.toString();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static void invoke(Method method, Object... arguments) {
    if (method == null) {
      return;
    }
    try {
      method.invoke(null, arguments);
    } catch (Throwable ignored) {
      // A backend that throws must never break the intercepted method.
    }
  }
}
