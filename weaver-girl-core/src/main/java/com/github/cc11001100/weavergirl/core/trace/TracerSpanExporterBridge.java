package com.github.cc11001100.weavergirl.core.trace;

import com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.core.exporter.SpanData;
import com.github.cc11001100.weavergirl.core.exporter.SpanExporter;

/**
 * Bridge connecting Tracer to SpanExporter.
 * Implements SpanCompletionListener and submits completed spans to SpanExporter.
 * @since 1.1.0
 */
public class TracerSpanExporterBridge implements SpanCompletionListener {

    private final SpanExporter exporter;

    public TracerSpanExporterBridge(SpanExporter exporter) {
        this.exporter = exporter;
    }

    @Override
    public void onSpanComplete(SpanContext span, long durationMs) {
        SpanData spanData = SpanData.builder()
                .traceId(span.getTraceId())
                .spanId(span.getSpanId())
                .parentSpanId(span.getParentSpanId())
                .operationName(span.getOperationName() != null ? span.getOperationName() : "unknown")
                .startTimeMs(span.getStartTimeMs())
                .durationMs(durationMs)
                .status("OK")
                .build();
        exporter.submit(spanData);
    }

    /**
     * Install this bridge: register as Tracer's completion listener.
     */
    public void install() {
        com.github.cc11001100.weavergirl.api.tracing.Tracer.setCompletionListener(this);
    }

    /**
     * Uninstall this bridge.
     */
    public void uninstall() {
        com.github.cc11001100.weavergirl.api.tracing.Tracer.setCompletionListener(null);
    }
}
