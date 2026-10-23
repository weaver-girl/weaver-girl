package com.github.cc11001100.weavergirl.api.tracing;

/**
 * Mutates or drops a finished span before anything else sees it.
 *
 * <p>This is the governance hook for application teams: remove noisy operations (health checks,
 * connection warm-up), redact sensitive attributes before they reach a collector, or attach
 * application metadata every span should carry. Because processors run inside {@link
 * Tracer#endSpan} before the completion listeners, one processor affects every consumer — the
 * built-in OTLP exporter, {@code SpanSink}s from plugins, and anything else subscribed to {@link
 * Tracer}.
 *
 * <p>Return the span to publish, or {@code null} to drop it. Dropping suppresses delivery to all
 * consumers; subsequent processors are not invoked. Use {@link SpanContext#toBuilder()} to start
 * from a copy of the finished span:
 *
 * <pre>
 * public final class RedactAuthorization implements SpanProcessor {
 *   public String name() { return "redact-authorization"; }
 *
 *   public SpanContext process(SpanContext span) {
 *     if (!span.getAttributes().containsKey("http.header.authorization")) {
 *       return span;
 *     }
 *     SpanContext.Builder copy = span.toBuilder();
 *     return copy.removeAttribute("http.header.authorization").build();
 *   }
 * }
 * </pre>
 *
 * <h3>Rules</h3>
 *
 * <ul>
 *   <li>Runs synchronously on the thread that ends the span. Keep it allocation-light and do not
 *       block: it sits on the application's request path.
 *   <li>An exception isolates that processor and leaves the span unchanged; the remaining
 *       processors still run. Failures are counted in {@link SpanProcessors#getStats()}.
 *   <li>Register through {@code PluginContext.registerSpanProcessor} so the processor is withdrawn
 *       when the plugin unloads.
 * </ul>
 *
 * @since 1.0.0
 */
public interface SpanProcessor {

  /** Stable name used for logging and metric labels. */
  String name();

  /**
   * Transform, keep, or drop a span that just ended.
   *
   * @param span the finished span, already carrying its final operation name and status
   * @return the span to publish, or {@code null} to drop it
   */
  SpanContext process(SpanContext span);
}
