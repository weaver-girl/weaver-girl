package com.github.cc11001100.weavergirl.api.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanLinkTest {

  @Test
  void builderCreatesImmutableLink() {
    Map<String, String> source = Map.of("kind", "batch");
    SpanLink link =
        SpanLink.builder()
            .traceId("trace")
            .spanId("span")
            .attribute("tenant", "acme")
            .attributes(source)
            .build();
    assertEquals("trace", link.getTraceId());
    assertEquals("span", link.getSpanId());
    assertEquals("acme", link.getAttributes().get("tenant"));
    assertEquals("batch", link.getAttributes().get("kind"));
    assertThrows(
        UnsupportedOperationException.class, () -> link.getAttributes().put("x", "y"));
    assertTrue(link.toString().contains("attrs=2"));
  }

  @Test
  void builderRequiresTraceAndSpanIds() {
    assertThrows(IllegalArgumentException.class, () -> SpanLink.builder().spanId("span").build());
    assertThrows(IllegalArgumentException.class, () -> SpanLink.builder().traceId("trace").build());
  }
}
