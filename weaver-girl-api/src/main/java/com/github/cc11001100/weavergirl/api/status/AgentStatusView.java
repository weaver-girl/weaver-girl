package com.github.cc11001100.weavergirl.api.status;

import java.util.Collections;
import java.util.Map;

/**
 * Read-only view of the agent's runtime counters.
 *
 * <p>The engine publishes its live status through {@link AgentStatusHolder}; optional modules (a
 * metrics bridge, an exporter) read it from here instead of depending on the engine's internal
 * status type. Every accessor must be safe to call concurrently.
 *
 * @since 1.0.0
 */
public interface AgentStatusView {

  /** Cumulative number of classes the agent has transformed. */
  long getTransformationCount();

  /** Cumulative number of class transformations that failed. */
  long getTransformationErrorCount();

  /** Cumulative number of interceptor advice invocations. */
  long getInterceptorInvocationCount();

  /** Cumulative number of interceptor advice invocations that threw. */
  long getInterceptorErrorCount();

  /** Number of interceptor definitions currently registered. */
  int getRegisteredInterceptorCount();

  /**
   * Per-interceptor counters, keyed by definition name.
   *
   * <p>The default returns an empty map, so a view that predates this method keeps compiling. The
   * engine's view returns one entry per definition that has been invoked at least once; the number
   * of entries is bounded by the registered definitions, so it is safe to render at every scrape.
   *
   * @return definition name to counters, never null
   * @since 1.0.0
   */
  default Map<String, ? extends InterceptorMetricsView> getInterceptorMetrics() {
    return Collections.emptyMap();
  }
}
