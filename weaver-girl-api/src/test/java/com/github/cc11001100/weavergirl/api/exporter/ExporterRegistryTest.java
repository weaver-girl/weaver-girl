package com.github.cc11001100.weavergirl.api.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExporterRegistryTest {

  @AfterEach
  void cleanup() {
    ExporterRegistry.shutdownAll();
  }

  @Test
  void registersActivatesExportsAndReportsHealth() {
    AtomicInteger exports = new AtomicInteger();
    AtomicInteger flushes = new AtomicInteger();
    DataExporter exporter =
        new DataExporter() {
          public String name() { return "test-exporter"; }
          public void init(Map<String, String> config) { assertEquals("v", config.get("k")); }
          public void export(InterceptorEvent event) { exports.incrementAndGet(); }
          public void flush() { flushes.incrementAndGet(); }
          public void shutdown() {}
          public boolean isHealthy() { return true; }
        };
    ExporterRegistry.register(null);
    ExporterRegistry.register(exporter);
    assertSame(exporter, ExporterRegistry.get("test-exporter"));
    assertTrue(ExporterRegistry.getExporterNames().contains("test-exporter"));
    assertFalse(ExporterRegistry.activate("missing"));
    assertTrue(ExporterRegistry.activate("test-exporter"));
    assertTrue(ExporterRegistry.activate("test-exporter"));
    assertEquals(java.util.List.of("test-exporter"), ExporterRegistry.getActiveExporterNames());
    ExporterRegistry.initExporter("test-exporter", Map.of("k", "v"));
    ExporterRegistry.initExporter("test-exporter", null);
    ExporterRegistry.exportEvent(null);
    ExporterRegistry.exportEvent(null);
    ExporterRegistry.flushAll();
    assertEquals(0, exports.get());
    assertEquals(1, flushes.get());
    assertEquals(Boolean.TRUE, ExporterRegistry.getHealthStatus().get("test-exporter"));
    ExporterRegistry.deactivate("test-exporter");
    assertTrue(ExporterRegistry.getActiveExporterNames().isEmpty());
    ExporterRegistry.unregister("test-exporter");
    assertNull(ExporterRegistry.get(null));
  }

  @Test
  void exporterFailuresDoNotEscapeAndShutdownClearsState() {
    DataExporter bad =
        new DataExporter() {
          public String name() { return "bad"; }
          public void export(InterceptorEvent event) { throw new RuntimeException(); }
          public void flush() { throw new RuntimeException(); }
          public void shutdown() { throw new RuntimeException(); }
          public boolean isHealthy() { return false; }
        };
    ExporterRegistry.register(bad);
    assertTrue(ExporterRegistry.activate("bad"));
    ExporterRegistry.exportEvent(
        InterceptorEvent.builder().type("x").className("C").methodName("m").build());
    ExporterRegistry.flushAll();
    assertEquals(Boolean.FALSE, ExporterRegistry.getHealthStatus().get("bad"));
    ExporterRegistry.shutdownAll();
    assertTrue(ExporterRegistry.getExporterNames().isEmpty());
  }
}
