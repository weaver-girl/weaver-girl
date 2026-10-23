package com.github.cc11001100.weavergirl.api.observability;

/**
 * Receives completed spans from the tracer.
 *
 * <p>This is the extension point for backends the agent does not ship: a product implements this
 * interface, registers it through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerSpanSink}
 * (or {@link SpanSinks#register}), and every span the tracer ends is delivered as a {@link
 * SpanRecord}. The built-in OTLP exporter is one consumer of the same spans; a sink runs alongside
 * it, not instead of it.
 *
 * <h3>Threading and cost</h3>
 *
 * <p>{@link #onSpanEnd} is called synchronously on the thread that ended the span, which is usually
 * an application thread. Keep it fast and do not block: queue the record and hand it to your own
 * worker if the backend can be slow. The tracer calls sinks in registration order and isolates
 * exceptions, so a failing sink does not stop the others or the application.
 *
 * <p>A sink sees every completed span, including unsampled ones; check {@link
 * SpanRecord#isSampled()} when that matters.
 *
 * @since 1.0.0
 */
public interface SpanSink {

  /**
   * A stable name used in logs and diagnostics, for example {@code "zipkin"}.
   *
   * @return the sink name, never null
   */
  String name();

  /**
   * Called once per completed span.
   *
   * <p>Implementations must be thread-safe: several application threads may call this at once.
   *
   * @param span the completed span, never null
   */
  void onSpanEnd(SpanRecord span);
}
