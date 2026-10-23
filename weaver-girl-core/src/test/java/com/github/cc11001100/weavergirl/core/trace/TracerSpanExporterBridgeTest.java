package com.github.cc11001100.weavergirl.core.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.exporter.SpanData;
import com.github.cc11001100.weavergirl.core.exporter.SpanExporter;
import com.github.cc11001100.weavergirl.core.exporter.SpanFormatter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for TracerSpanExporterBridge connecting Tracer to SpanExporter. */
class TracerSpanExporterBridgeTest {

  private SpanExporter exporter;
  private SpanFormatter.InMemorySpanFormatter inMemory;
  private TracerSpanExporterBridge bridge;

  @BeforeEach
  void setUp() {
    Tracer.clearCurrentSpan();
    Tracer.setCompletionListener(null);

    exporter = new SpanExporter();
    inMemory = new SpanFormatter.InMemorySpanFormatter();
    exporter.addFormatter(inMemory);
    exporter.start();

    bridge = new TracerSpanExporterBridge(exporter);
    bridge.install();
  }

  @AfterEach
  void tearDown() {
    bridge.uninstall();
    exporter.stop();
    Tracer.clearCurrentSpan();
  }

  @Test
  void endSpan_shouldSubmitToExporter() {
    Tracer.startSpan();
    SpanContext ended = Tracer.endSpan("test-op", "OK");

    assertNotNull(ended);
    exporter.flush();

    List<SpanData> spans = inMemory.getSpans();
    assertEquals(1, spans.size());

    SpanData data = spans.get(0);
    assertEquals(ended.getTraceId(), data.getTraceId());
    assertEquals(ended.getSpanId(), data.getSpanId());
  }

  @Test
  void endSpan_shouldCalculateDuration() throws InterruptedException {
    Tracer.startSpan();
    Thread.sleep(50);
    Tracer.endSpan("timed-op", "OK");

    exporter.flush();

    List<SpanData> spans = inMemory.getSpans();
    assertEquals(1, spans.size());

    SpanData data = spans.get(0);
    assertTrue(
        data.getDurationMs() >= 50,
        "Duration should be at least 50ms but was " + data.getDurationMs());
  }

  @Test
  void endSpan_mapsTheSpanKindAttributeAndKeepsOtherAttributes() {
    Tracer.startSpan("GET /orders");
    Tracer.setAttribute(Tracer.SPAN_KIND_ATTRIBUTE, "server");
    Tracer.setAttribute("http.method", "GET");
    Tracer.endSpan(null, "OK");

    exporter.flush();
    SpanData data = inMemory.getSpans().get(0);
    assertEquals(2, data.getKind());
    assertEquals("GET", data.getAttributes().get("http.method"));
    assertFalse(data.getAttributes().containsKey(Tracer.SPAN_KIND_ATTRIBUTE));
  }

  @Test
  void unknownSpanKindsFallBackToInternal() {
    assertEquals(1, TracerSpanExporterBridge.kindOf(null));
    assertEquals(1, TracerSpanExporterBridge.kindOf("nonsense"));
    assertEquals(3, TracerSpanExporterBridge.kindOf("CLIENT"));
  }

  @Test
  void endSpan_shouldIncludeOperationName() {
    Tracer.startSpan();
    Tracer.endSpan("HTTP GET /api/users", "OK");

    exporter.flush();

    List<SpanData> spans = inMemory.getSpans();
    assertEquals(1, spans.size());

    SpanData data = spans.get(0);
    assertEquals("HTTP GET /api/users", data.getOperationName());
  }
}
