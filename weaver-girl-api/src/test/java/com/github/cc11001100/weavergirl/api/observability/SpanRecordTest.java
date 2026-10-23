package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

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
}
