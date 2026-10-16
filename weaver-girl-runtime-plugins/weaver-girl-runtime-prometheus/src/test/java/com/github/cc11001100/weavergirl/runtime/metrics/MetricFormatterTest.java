package com.github.cc11001100.weavergirl.runtime.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for MetricFormatter implementations.
 */
class MetricFormatterTest {

  @Test
  void jsonFormatter_createsInstance() {
    MetricFormatter formatter = new MetricFormatter.JsonFormatter();
    assertNotNull(formatter);
  }

  @Test
  void jsonFormatter_formatsSnapshot() {
    MetricFormatter formatter = new MetricFormatter.JsonFormatter();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("testKey", "testValue");
    values.put("count", 42);
    MetricSnapshot snapshot = new MetricSnapshot(System.currentTimeMillis(), values);

    // Should not throw
    assertDoesNotThrow(() -> formatter.format(snapshot));
  }

  @Test
  void logFormatter_createsInstance() {
    MetricFormatter formatter = new MetricFormatter.LogFormatter();
    assertNotNull(formatter);
  }

  @Test
  void logFormatter_formatsSnapshot() {
    MetricFormatter formatter = new MetricFormatter.LogFormatter();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("key1", "value1");
    values.put("key2", 123);
    MetricSnapshot snapshot = new MetricSnapshot(System.currentTimeMillis(), values);

    // Should not throw
    assertDoesNotThrow(() -> formatter.format(snapshot));
  }

  @Test
  void logFormatter_formatsEmptySnapshot() {
    MetricFormatter formatter = new MetricFormatter.LogFormatter();
    Map<String, Object> values = new LinkedHashMap<>();
    MetricSnapshot snapshot = new MetricSnapshot(System.currentTimeMillis(), values);

    assertDoesNotThrow(() -> formatter.format(snapshot));
  }
}
