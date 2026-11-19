package com.github.cc11001100.weavergirl.plugins.logging;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.ApplicationClasspath;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Logging support plugin. Two things, both about log lines:
 *
 * <ol>
 *   <li><b>Log-to-trace correlation.</b> While an intercepted call runs, the active {@code
 *       traceId}/{@code spanId} and any configured baggage keys are written into whichever logging
 *       backend the application actually reads — SLF4J MDC for Logback and slf4j bindings, Log4j2
 *       {@code ThreadContext} for a Log4j2 application — and restored on exit. A log line emitted
 *       inside an intercepted call therefore carries the trace id with no code change in the
 *       application.
 *   <li><b>Log-capture events.</b> The static {@link #injectContext(String, String)} API other
 *       plugins call publishes a {@code log-capture} event and writes the method name into MDC.
 * </ol>
 *
 * <p>The correlation interceptors are registered only for backends that are actually present ({@link
 * PluginContext#classPresent}). When neither is, the plugin registers nothing and stays inert — on
 * unload it drops its {@link LogCorrelation} instance, which is the only thing that holds resolved
 * backend methods, so a backend jar is not pinned after the plugin goes away.
 *
 * <p><b>Note on class-name matching.</b> The built-in logging plugin does not target a library class:
 * it targets whatever the application calls, through the runtime definitions registered below, and
 * matches them by the annotation class names ({@code @Slf4j}-annotated classes are not visible at
 * runtime, so the match is on log-statement entry points the backend exposes).
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code mdcKeys} — Comma-separated MDC keys to set (default: traceId,spanId,method)
 *   <li>{@code baggageKeys} — Extra ThreadContext keys to mirror into MDC/ThreadContext (default:
 *       empty)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 *
 * @since 1.0.0
 */
public class LoggingPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(LoggingPlugin.class);

  /**
   * Classes whose {@code info}/{@code warn}/{@code error}/{@code debug} calls are wrapped while a
   * trace is active. These are the two concrete backends' entry points; wrapping them is cheap and
   * covers every log statement the application makes, including ones outside intercepted calls.
   */
  private static final String LOGBACK_LOGGER = "ch.qos.logback.classic.Logger";

  private static final String LOG4J2_LOGGER = "org.apache.logging.log4j.core.Logger";

  /**
   * The methods that actually emit a log line. The {@code isXxxEnabled} guards are deliberately not
   * included: they are hot-path checks called on every branch, and wrapping them would cost more
   * than the correlation is worth.
   */
  private static final String LOG_METHODS = "info|warn|error|debug|trace|log|fatal";

  private String[] mdcKeys = {"traceId", "spanId", "method"};
  private String[] baggageKeys = new String[0];
  private boolean enabled = true;

  /** Resolved once per plugin instance; dropped with the plugin. */
  private volatile LogCorrelation correlation;

  @Override
  public String name() {
    return "logging";
  }

  /**
   * The two backends this plugin can correlate. The loader activates it when either is visible; with
   * neither present the plugin is inert, which is the documented "unloads cleanly when the logging
   * backend is absent" behaviour — it never reaches {@code registerInterceptors}.
   */
  @Override
  public String[] instrumentedLibraries() {
    return new String[] {LOGBACK_LOGGER, LOG4J2_LOGGER};
  }

  @Override
  public void init(PluginContext context) {
    String mdcKeysStr = context.getConfig("mdcKeys", "traceId,spanId,method");
    mdcKeys = splitKeys(mdcKeysStr);
    baggageKeys = splitKeys(context.getConfig("baggageKeys", ""));
    enabled = context.getConfigBoolean("enabled", true);
    correlation = new LogCorrelation();
  }

  @Override
  public void registerInterceptors(
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
    if (!enabled) {
      return;
    }
    LogCorrelation backend = correlation;
    if (backend == null || !backend.anyBackendAvailable()) {
      // No logging backend on the classpath: nothing to correlate, and no pointcut to weave.
      return;
    }
    // Register a pointcut only for a backend that is both resolved (so its ThreadContext/MDC
    // methods exist) and visible from the plugin's loader chain, which reaches the application.
    // Registering against an absent backend would weave a pointcut that matches nothing.
    ClassLoader loader = getClass().getClassLoader();
    List<String> targets = new ArrayList<String>();
    if (backend.slf4jAvailable()
        && (classVisible(loader, LOGBACK_LOGGER) || ApplicationClasspath.present(LOGBACK_LOGGER))) {
      targets.add(LOGBACK_LOGGER);
    }
    if (backend.log4j2Available()
        && (classVisible(loader, LOG4J2_LOGGER) || ApplicationClasspath.present(LOG4J2_LOGGER))) {
      targets.add(LOG4J2_LOGGER);
    }

    for (String target : targets) {
      final String className = target;
      Interceptor interceptor =
          new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
              Map<String, String> entries = activeContextEntries();
              if (entries.isEmpty()) {
                return;
              }
              inv.setAttachment("logging.previous", backend.inject(entries));
            }

            @Override
            public void after(MethodInvocation inv) {
              backend.restore(previousOf(inv));
            }

            @Override
            public void onException(MethodInvocation inv) {
              backend.restore(previousOf(inv));
            }
          };

      registry.register(
          intercept(className)
              .methodPattern(LOG_METHODS)
              .around(interceptor::before, interceptor::after)
              .onException(interceptor::onException)
              .priority(1)
              .build());
    }
  }

  /** The key/value pairs that belong in the logging context right now. */
  Map<String, String> activeContextEntries() {
    Map<String, String> entries = new LinkedHashMap<String, String>();
    for (String key : mdcKeys) {
      if (key.isEmpty() || LoggingPlugin.class.getSimpleName().equals(key)) {
        continue;
      }
      String value = ThreadContext.get(key);
      if (value != null && !value.isEmpty()) {
        entries.put(key, value);
      }
    }
    for (String key : baggageKeys) {
      if (key.isEmpty()) {
        continue;
      }
      String value = ThreadContext.get(key);
      if (value != null && !value.isEmpty()) {
        entries.put(key, value);
      }
    }
    return entries;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> previousOf(MethodInvocation inv) {
    Object previous = inv.getAttachment("logging.previous");
    return previous instanceof Map ? (Map<String, String>) previous : null;
  }

  @Override
  public String[] depends() {
    return new String[] {"trace-correlation"};
  }

  // ---- Static utility methods for other plugins ----

  /**
   * Publish a {@code log-capture} event for {@code className}.{@code methodName} and write the method
   * name into the SLF4J MDC.
   *
   * <p>Trace and span ids are written by the automatic correlation interceptors while a trace is
   * active; this method only records the interception event and the method name. It reads the MDC
   * unconditionally because it is called by other plugins, not from woven code.
   */
  public static void injectContext(String className, String methodName) {
    String traceId = ThreadContext.get("traceId");
    String spanId = ThreadContext.get("spanId");
    if (traceId != null) {
      MDC.put("traceId", traceId);
    }
    if (spanId != null) {
      MDC.put("spanId", spanId);
    }
    MDC.put("method", className + "." + methodName);
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("log-capture")
                .plugin("logging")
                .className(className)
                .methodName(methodName)
                .attribute("traceId", traceId != null ? traceId : "")
                .build());
  }

  /** Remove the MDC entries written by {@link #injectContext(String, String)}. */
  public static void clearContext() {
    MDC.remove("traceId");
    MDC.remove("spanId");
    MDC.remove("method");
  }

  // Expose for testing
  String[] getMdcKeys() {
    return mdcKeys.clone();
  }

  String[] getBaggageKeys() {
    return baggageKeys.clone();
  }

  boolean isEnabled() {
    return enabled;
  }

  /**
   * Whether {@code name} can be seen from the plugin's loader chain without defining it.
   *
   * <p>The check walks the chain because the plugin's own loader is child-first and carries only the
   * agent; the application's backend classes live behind it.
   */
  private static boolean classVisible(ClassLoader loader, String name) {
    String resource = name.replace('.', '/') + ".class";
    try {
      for (ClassLoader current = loader; current != null; current = current.getParent()) {
        if (current.getResource(resource) != null) {
          return true;
        }
      }
      ClassLoader context = Thread.currentThread().getContextClassLoader();
      return context != null && context.getResource(resource) != null;
    } catch (Throwable e) {
      return false;
    }
  }

  private static String[] splitKeys(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return new String[0];
    }
    String[] parts = raw.split(",");
    List<String> keys = new ArrayList<String>(parts.length);
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        keys.add(trimmed);
      }
    }
    return keys.toArray(new String[0]);
  }
}
