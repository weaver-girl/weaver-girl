package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanRecordTest {

  @Test
  void builderDefaultsAndDerivedValues() {
    SpanRecord record =
        SpanRecord.builder()
            .traceId("trace")
            .spanId("span")
            .startTimeMs(1_000)
            .durationMs(250)
            .build();

    assertEquals(SpanRecord.KIND_INTERNAL, record.getKind());
    assertEquals("unknown", record.getOperationName());
    assertEquals(1_250, record.getEndTimeMs());
    assertTrue(record.isSampled());
    assertFalse(record.isError());
    assertNull(record.getParentSpanId());
    assertTrue(record.getAttributes().isEmpty());
    assertTrue(record.getBaggage().isEmpty());
  }

  @Test
  void negativeDurationIsClampedAndMissingIdsAreRejected() {
    SpanRecord record =
        SpanRecord.builder()
            .traceId("trace")
            .spanId("span")
            .durationMs(-5)
            .build();
    assertEquals(0, record.getDurationMs());

    assertThrows(
        IllegalArgumentException.class, () -> SpanRecord.builder().spanId("span").build());
    assertThrows(
        IllegalArgumentException.class, () -> SpanRecord.builder().traceId("trace").build());
  }

  @Test
  void errorStatusAndErrorAttributeBothMeanError() {
    SpanRecord failed =
        SpanRecord.builder().traceId("t").spanId("s").status("ERROR").build();
    SpanRecord flagged =
        SpanRecord.builder().traceId("t").spanId("s").attribute("error", "true").build();

    assertTrue(failed.isError());
    assertTrue(flagged.isError());
  }

  @Test
  void attributesAndBaggageAreImmutableSnapshots() {
    Map<String, String> attributes = new java.util.HashMap<>();
    attributes.put("http.method", "GET");
    SpanRecord record =
        SpanRecord.builder()
            .traceId("t")
            .spanId("s")
            .attributes(attributes)
            .baggage(Collections.singletonMap("tenant", "acme"))
            .build();

    attributes.put("http.method", "POST");

    assertEquals("GET", record.getAttribute("http.method"));
    assertEquals("acme", record.getBaggage().get("tenant"));
    assertThrows(
        UnsupportedOperationException.class, () -> record.getAttributes().put("x", "y"));
  }

  @Test
  void conversionLiftsKindNormalizesNamesAndKeepsTheRest() {
    SpanContext span =
        SpanContext.builder()
            .traceId("trace")
            .spanId("span")
            .parentSpanId("parent")
            .attribute(Tracer.SPAN_KIND_ATTRIBUTE, " SERVER ")
            .attribute("http.method", "GET")
            .baggage("tenant", "acme")
            .errorStatus("ERROR")
            .build();

    SpanRecord record = SpanRecord.from(span, -3);

    assertEquals("trace", record.getTraceId());
    assertEquals("span", record.getSpanId());
    assertEquals("parent", record.getParentSpanId());
    assertEquals("server", record.getKind());
    assertEquals("unknown", record.getOperationName());
    assertEquals("ERROR", record.getStatus());
    assertEquals(0, record.getDurationMs());
    assertEquals("GET", record.getAttribute("http.method"));
    assertNull(record.getAttribute(Tracer.SPAN_KIND_ATTRIBUTE));
    assertEquals("acme", record.getBaggage().get("tenant"));
    assertTrue(record.toString().contains("trace=trace"));

    SpanContext named =
        SpanContext.builder().traceId("trace").spanId("span").operationName("GET /orders").build();
    assertEquals("GET /orders", SpanRecord.from(named, 5).getOperationName());
  }

  @Test
  void anEmptyKindAndOperationNameFallBackToTheDefaults() {
    SpanRecord record =
        SpanRecord.builder().traceId("t").spanId("s").kind("").operationName("").build();

    assertEquals(SpanRecord.KIND_INTERNAL, record.getKind());
    assertEquals("unknown", record.getOperationName());
    assertNull(record.getStatus());
    assertFalse(record.isError());
  }
}
