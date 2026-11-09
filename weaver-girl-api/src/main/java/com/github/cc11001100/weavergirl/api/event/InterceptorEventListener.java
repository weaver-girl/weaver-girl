package com.github.cc11001100.weavergirl.api.event;

/**
 * Listener for interceptor events. Implementations can consume structured events for: - Metrics
 * export (Prometheus, StatsD) - Trace export (Zipkin, Jaeger, OTLP) - Structured logging (JSON to
 * ELK/Datadog/CloudWatch) - Custom monitoring dashboards
 *
 * <p>A plugin registers through {@link
 * com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerEventListener}, so the
 * subscription is withdrawn with the plugin. {@link
 * InterceptorEventPublisher#addListener(InterceptorEventListener)} is the direct entry and is not
 * withdrawn automatically.
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
