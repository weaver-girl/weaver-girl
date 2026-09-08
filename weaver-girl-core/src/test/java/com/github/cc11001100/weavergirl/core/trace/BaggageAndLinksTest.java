package com.github.cc11001100.weavergirl.core.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanLink;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.exporter.SpanData;
import com.github.cc11001100.weavergirl.core.exporter.SpanExporter;
import com.github.cc11001100.weavergirl.core.exporter.SpanFormatter;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for baggage propagation and span links. */
class BaggageAndLinksTest {

  private SpanExporter exporter;
  private SpanFormatter.InMemorySpanFormatter inMemory;
  private TracerSpanExporterBridge bridge;

  @BeforeEach
  void setUp() {
    exporter = new SpanExporter(10, 60000, 1000);
    inMemory = new SpanFormatter.InMemorySpanFormatter(1000);
    exporter.addFormatter(inMemory);
    bridge = new TracerSpanExporterBridge(exporter);
    bridge.install();
  }

  @AfterEach
  void tearDown() {
    bridge.uninstall();
    Tracer.clearCurrentSpan();
    if (exporter.isRunning()) {
      exporter.stop();
    }
  }

  @Test
  void baggage_shouldPropagateViaTracer() {
    SpanContext span = Tracer.startSpan();
    Tracer.setBaggage("userId", "12345");
    Tracer.setBaggage("tenant", "acme");

    assertEquals("12345", Tracer.getBaggage("userId"));
    assertEquals("acme", Tracer.getBaggage("tenant"));

    Tracer.endSpan("test-op", "OK");
  }

  @Test
  void baggage_shouldPropagateToChildSpan() {
    SpanContext parent = Tracer.startSpan();
    Tracer.setBaggage("requestId", "req-abc");
    Tracer.setBaggage("source", "gateway");

    SpanContext child = Tracer.startChildSpan();

    assertEquals("req-abc", child.getBaggageItem("requestId"));
    assertEquals("gateway", child.getBaggageItem("source"));

    Tracer.endSpan("child-op", "OK");
    Tracer.endSpan("parent-op", "OK");
  }

  @Test
  void baggage_shouldExportAsAttributes() {
    SpanContext span = Tracer.startSpan();
    Tracer.setBaggage("env", "production");
    Tracer.setBaggage("version", "2.0");
    Tracer.endSpan("baggage-op", "OK");

    exporter.flush();

    assertEquals(1, inMemory.size());
    SpanData exported = inMemory.getSpans().get(0);
    Map<String, String> attrs = exported.getAttributes();
    assertEquals("production", attrs.get("baggage.env"));
    assertEquals("2.0", attrs.get("baggage.version"));
  }

  @Test
  void spanLink_shouldBuildCorrectly() {
    SpanLink link =
        SpanLink.builder()
            .traceId("abc123")
            .spanId("def456")
            .attribute("reason", "batch")
            .attribute("source", "queue")
            .build();

    assertEquals("abc123", link.getTraceId());
    assertEquals("def456", link.getSpanId());
    assertEquals(2, link.getAttributes().size());
    assertEquals("batch", link.getAttributes().get("reason"));
    assertEquals("queue", link.getAttributes().get("source"));
  }

  @Test
  void spanLink_shouldRequireTraceIdAndSpanId() {
    assertThrows(IllegalArgumentException.class, () -> SpanLink.builder().build());
    assertThrows(IllegalArgumentException.class, () -> SpanLink.builder().traceId("abc").build());
    assertThrows(IllegalArgumentException.class, () -> SpanLink.builder().spanId("def").build());
  }

  @Test
  void spanLink_shouldIncludeInSpanData() {
    SpanLink link1 =
        SpanLink.builder().traceId("traceA").spanId("spanA").attribute("type", "parent").build();

    SpanLink link2 = SpanLink.builder().traceId("traceB").spanId("spanB").build();

    SpanData spanData =
        SpanData.builder()
            .traceId("mainTrace")
            .spanId("mainSpan")
            .operationName("batch-process")
            .startTimeMs(System.currentTimeMillis())
            .durationMs(50)
            .links(Arrays.asList(link1, link2))
            .build();

    assertEquals(2, spanData.getLinks().size());
    assertEquals("traceA", spanData.getLinks().get(0).getTraceId());
    assertEquals("spanA", spanData.getLinks().get(0).getSpanId());
    assertEquals("parent", spanData.getLinks().get(0).getAttributes().get("type"));
    assertEquals("traceB", spanData.getLinks().get(1).getTraceId());
    assertTrue(spanData.getLinks().get(1).getAttributes().isEmpty());
  }

  @Test
  void spanLink_shouldAppearInOtlpJson() {
    SpanLink link =
        SpanLink.builder().traceId("t1").spanId("s1").attribute("reason", "fan-out").build();

    SpanData spanData =
        SpanData.builder()
            .traceId("mainTrace")
            .spanId("mainSpan")
            .operationName("op")
            .startTimeMs(1000)
            .durationMs(10)
            .links(Arrays.asList(link))
            .build();

    String json = spanData.toOtlpJson();
    assertTrue(json.contains("\"links\":["));
    assertTrue(json.contains("\"traceId\":\"t1\""));
    assertTrue(json.contains("\"spanId\":\"s1\""));
    assertTrue(json.contains("\"reason\""));
    assertTrue(json.contains("\"fan-out\""));
  }
}
