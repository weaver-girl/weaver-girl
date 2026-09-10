package com.github.cc11001100.weavergirl.core.metrics;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges weaver-girl internal metrics into a Micrometer {@link MeterRegistry}.
 *
 * <p>This is optional: if Micrometer is not on the classpath, this class is a no-op. When present,
 * it exposes:</p>
 *
 * <ul>
 *   <li>{@code weavergirl.transformations.total} — cumulative class transformations</li>
 *   <li>{@code weavergirl.interceptor.invocations.total} — cumulative advice invocations</li>
 *   <li>{@code weavergirl.interceptor.errors.total} — cumulative advice errors</li>
 *   <li>{@code weavergirl.interceptor.hook.{name}.timer} — per-hook latency histogram (Timer)</li>
 *   <li>{@code weavergirl.registered.interceptors} — current registered interceptor count (Gauge)
 *   </li>
 * </ul>
 *
 * <p>Usage:</p>
 *
 * <pre>
 *   io.micrometer.core.instrument.MeterRegistry registry =
 *       new io.micrometer.prometheus.PrometheusMeterRegistry(...);
 *   com.github.cc11001100.weavergirl.core.metrics.MicrometerBridge.start(registry);
 * </pre>
 *
 * @since 2.0.0
 */
public class MicrometerBridge {

  private static final Logger log = LoggerFactory.getLogger(MicrometerBridge.class);
  private static volatile MeterRegistry meterRegistry;
  private static volatile boolean started;

  private static final Map<String, Timer> hookTimers = new ConcurrentHashMap<>();
  private static Counter transformationCounter;
  private static Counter invocationCounter;
  private static Counter errorCounter;

  private MicrometerBridge() {}

  /**
   * Start the Micrometer bridge with the given registry.
   *
   * <p>If Micrometer is not available, this logs a warning and returns without effect.
   *
   * @param registry the Micrometer registry to bind to
   */
  public static void start(MeterRegistry registry) {
    if (registry == null) {
      log.warn("MicrometerBridge.start() called with null registry; ignoring");
      return;
    }
    if (started) {
      log.warn("MicrometerBridge already started; ignoring duplicate start()");
      return;
    }
    meterRegistry = registry;
    started = true;
    registerCoreMetrics();
    registerLifecycleListeners();
    log.info("MicrometerBridge started");
  }

  /**
   * Stop the Micrometer bridge and release references.
   */
  public static void stop() {
    if (!started) {
      return;
    }
    try {
      InterceptorEventPublisher.getInstance().removeListener(lifecycleListener);
    } catch (Throwable ignored) {
    }
    hookTimers.clear();
    transformationCounter = null;
    invocationCounter = null;
    errorCounter = null;
    meterRegistry = null;
    started = false;
    log.info("MicrometerBridge stopped");
  }

  /** Return true if the bridge is currently started. */
  public static boolean isStarted() {
    return started;
  }

  private static void registerCoreMetrics() {
    if (meterRegistry == null) {
      return;
    }
    transformationCounter =
        Counter.builder("weavergirl.transformations.total")
            .description("Cumulative class transformations performed by the agent")
            .register(meterRegistry);
    invocationCounter =
        Counter.builder("weavergirl.interceptor.invocations.total")
            .description("Cumulative interceptor advice invocations")
            .register(meterRegistry);
    errorCounter =
        Counter.builder("weavergirl.interceptor.errors.total")
            .description("Cumulative interceptor advice errors")
            .register(meterRegistry);

    Gauge.builder("weavergirl.registered.interceptors", AgentStatus.getInstance(), AgentStatus::getRegisteredInterceptorCount)
        .description("Current number of registered interceptor definitions")
        .register(meterRegistry);
  }

  private static void registerLifecycleListeners() {
    try {
      InterceptorEventPublisher.getInstance().addListener(lifecycleListener);
    } catch (Throwable t) {
      log.warn("MicrometerBridge failed to register lifecycle listener: {}", t.getMessage());
    }
  }

  private static final InterceptorEventListener lifecycleListener =
      new InterceptorEventListener() {
        @Override
        public void onEvent(InterceptorEvent event) {
          if (meterRegistry == null || event == null) {
            return;
          }
          String type = event.getType();
          if (type == null) {
            return;
          }
          if ("transformation".equals(type)) {
            transformationCounter.increment();
            return;
          }
          if ("interceptor".equals(type)) {
            invocationCounter.increment();
            String methodName = event.getMethodName();
            if (methodName != null && !methodName.isEmpty()) {
              Timer timer =
                  hookTimers.computeIfAbsent(
                      methodName,
                      n ->
                          Timer.builder("weavergirl.interceptor.hook")
                              .description("Latency histogram per interceptor hook point")
                              .tags("name", n)
                              .register(meterRegistry));
              timer.record(event.getDurationMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return;
          }
          if ("interceptor.error".equals(type)) {
            errorCounter.increment();
          }
        }
      };
}
