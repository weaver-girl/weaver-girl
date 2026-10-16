package com.github.cc11001100.weavergirl.core.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanLink;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** The OTLP rendering only emits a field when the span actually has something to say there. */
class SpanDataTest {

  @Test
  void aBareSpanOmitsParentAttributesAndLinks() {
    SpanData span = SpanData.builder().traceId("t1").spanId("s1").operationName("op").build();

    assertNull(span.getParentSpanId());
    assertEquals(0L, span.getStartTimeMs());
    assertEquals("OK", span.getStatus());
    assertTrue(span.getAttributes().isEmpty());
    assertTrue(span.getLinks().isEmpty());

    String json = span.toOtlpJson();
    assertFalse(json.contains("parentSpanId"));
    assertFalse(json.contains("\"attributes\""));
    assertFalse(json.contains("\"links\""));
    assertTrue(span.toString().contains("trace=t1"));
  }

  @Test
  void attributesAndLinksAreRenderedInOrder() {
    SpanLink bare = SpanLink.builder().traceId("lt").spanId("ls").build();
    SpanLink tagged =
        SpanLink.builder().traceId("lt2").spanId("ls2").attribute("k", "v").attribute("k2", "v2").build();

    SpanData span =
        SpanData.builder()
            .traceId("t1")
            .spanId("s1")
            .parentSpanId("p1")
            .operationName("op")
            .startTimeMs(5)
            .durationMs(7)
            .status("ERROR")
            .attribute("zone", "east")
            .attribute("kind", "db")
            .link(null)
            .link(bare)
            .links(Arrays.asList(tagged, bare))
            .build();

    assertEquals("p1", span.getParentSpanId());
    assertEquals(5L, span.getStartTimeMs());
    assertEquals("ERROR", span.getStatus());
    assertEquals(2, span.getAttributes().size());
    assertEquals(2, span.getLinks().size());
    assertThrows(UnsupportedOperationException.class, () -> span.getAttributes().put("x", "y"));
    assertThrows(UnsupportedOperationException.class, () -> span.getLinks().clear());

    String json = span.toOtlpJson();
    assertTrue(json.contains("\"parentSpanId\":\"p1\""));
    assertTrue(json.contains("\"startTimeUnixNano\":\"5000000\""));
    assertTrue(json.contains("\"endTimeUnixNano\":\"12000000\""));
    assertTrue(json.contains("\"code\":\"ERROR\""));
    // Two attributes, so the separator between them is exercised, and order is preserved.
    assertTrue(json.contains("{\"key\":\"zone\",\"value\":{\"stringValue\":\"east\"}},{\"key\":\"kind\""));
    // The second link has no attributes of its own, so its attribute block is skipped.
    assertTrue(json.contains("\"links\":["));
    assertTrue(json.contains("{\"key\":\"k\",\"value\":{\"stringValue\":\"v\"}},{\"key\":\"k2\""));
    assertTrue(json.contains("\"traceId\":\"lt\""));
  }

  @Test
  void replacingLinksDropsTheOnesSetEarlier() {
    SpanLink first = SpanLink.builder().traceId("a").spanId("b").build();
    SpanData span =
        SpanData.builder()
            .traceId("t")
            .spanId("s")
            .operationName("op")
            .link(first)
            .links(Collections.emptyList())
            .build();

    assertTrue(span.getLinks().isEmpty());
    assertFalse(span.toOtlpJson().contains("\"links\""));
  }
}
