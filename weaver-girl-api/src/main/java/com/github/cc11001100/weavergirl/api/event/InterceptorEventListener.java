package com.github.cc11001100.weavergirl.api.event;

/**
 * Listener for interceptor events. Implementations can consume structured events for: - Metrics
 * export (Prometheus, StatsD) - Trace export (Zipkin, Jaeger, OTLP) - Structured logging (JSON to
 * ELK/Datadog/CloudWatch) - Custom monitoring dashboards
 *
 * <p>Register via {@link InterceptorEventPublisher#addListener(InterceptorEventListener)}.
 *
 * <p>Example: JSON logging listener
 *
 * <pre>
 * InterceptorEventPublisher.getInstance().addListener(event -&gt; {
 *     System.out.println(new ObjectMapper().writeValueAsString(event.toMap()));
 * });
 * </pre>
 */
@FunctionalInterface
public interface InterceptorEventListener {

  /**
   * Called when an interceptor event is emitted.
   *
   * @param event the structured event
   */
  void onEvent(InterceptorEvent event);
}
