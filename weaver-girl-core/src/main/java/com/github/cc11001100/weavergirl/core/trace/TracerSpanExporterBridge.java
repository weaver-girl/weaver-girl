package com.github.cc11001100.weavergirl.core.trace;

import com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.exporter.SpanData;
import com.github.cc11001100.weavergirl.core.exporter.SpanExporter;
import java.util.Map;

/**
 * Bridge connecting Tracer to SpanExporter. Implements SpanCompletionListener and submits completed
 * spans to SpanExporter.
 *
 * @since 1.1.0
 */
public class TracerSpanExporterBridge implements SpanCompletionListener {

  private final SpanExporter exporter;

  public TracerSpanExporterBridge(SpanExporter exporter) {
    this.exporter = exporter;
  }

  @Override
  public void onSpanComplete(SpanContext span, long durationMs) {
    SpanData.Builder spanDataBuilder =
        SpanData.builder()
            .traceId(span.getTraceId())
            .spanId(span.getSpanId())
            .parentSpanId(span.getParentSpanId())
            .operationName(span.getOperationName() != null ? span.getOperationName() : "unknown")
            .startTimeMs(span.getStartTimeMs())
            .durationMs(durationMs)
            .status(span.getErrorStatus() != null ? span.getErrorStatus() : "OK")
            .kind(kindOf(span.getAttribute(Tracer.SPAN_KIND_ATTRIBUTE)));

    // Span attributes first (http.method, db.system, error.*, ...); they describe this span.
    // "span.kind" is consumed into the OTLP kind field above, not repeated as an attribute.
    for (Map.Entry<String, String> entry : span.getAttributes().entrySet()) {
      if (Tracer.SPAN_KIND_ATTRIBUTE.equals(entry.getKey())) {
        continue;
      }
      spanDataBuilder.attribute(entry.getKey(), entry.getValue());
    }

    // Baggage is request-scoped propagation state; keep it visible but namespaced.
    for (Map.Entry<String, String> entry : span.getBaggage().entrySet()) {
      spanDataBuilder.attribute("baggage." + entry.getKey(), entry.getValue());
    }

    // Populate parent link when present so downstream exporters/topology can
    // reconstruct causal relationships without re-parsing parentSpanId.
    if (span.getParentSpanId() != null) {
      spanDataBuilder.link(
          com.github.cc11001100.weavergirl.api.tracing.SpanLink.builder()
              .traceId(span.getTraceId())
              .spanId(span.getParentSpanId())
              .build());
    }

    exporter.submit(spanDataBuilder.build());
  }

  /** OTLP span kind enum values; INTERNAL is the default for interposed method spans. */
  static int kindOf(String kind) {
    if (kind == null) {
      return 1;
    }
    switch (kind.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "server":
        return 2;
      case "client":
        return 3;
      case "producer":
        return 4;
      case "consumer":
        return 5;
      default:
        return 1;
    }
  }

  /** Install this bridge: register as Tracer's completion listener. */
  public void install() {
    com.github.cc11001100.weavergirl.api.tracing.Tracer.setCompletionListener(this);
  }

  /**
   * Uninstall this bridge.
   *
   * <p>Removes only this listener: {@code setCompletionListener(null)} would also detach every other
   * consumer, such as the topology bridge.
   */
  public void uninstall() {
    com.github.cc11001100.weavergirl.api.tracing.Tracer.removeCompletionListener(this);
  }
}
