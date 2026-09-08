package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for P79 bootstrap wiring: {@link WeaverGirl#initSpanExport} creates a batch exporter +
 * completion-listener bridge only when span export is configured, and {@link
 * WeaverGirl#stopSpanExport} tears it down.
 */
class WeaverGirlSpanExportTest {

  private WeaverGirl created;

  @AfterEach
  void tearDown() {
    if (created != null) {
      created.stopSpanExport();
      created = null;
    }
    Tracer.clearCurrentSpan();
    Tracer.setCompletionListener(null);
  }

  @Test
  void noConfig_createsNothing() {
    created = WeaverGirl.create();
    created.initSpanExport(Collections.emptyMap());
    assertNull(created.getSpanExporter());
  }

  @Test
  void nullConfig_createsNothing() {
    created = WeaverGirl.create();
    created.initSpanExport(null);
    assertNull(created.getSpanExporter());
  }

  @Test
  void logOnlyExporter_createdAndRunning() {
    created = WeaverGirl.create();
    Map<String, String> config = new HashMap<>();
    config.put("spanExport", "true");
    created.initSpanExport(config);

    assertNotNull(created.getSpanExporter());
    assertTrue(created.getSpanExporter().isRunning());
  }

  @Test
  void logOnlyExporter_receivesCompletedSpans() {
    created = WeaverGirl.create();
    Map<String, String> config = new HashMap<>();
    config.put("spanExport", "true");
    created.initSpanExport(config);

    Tracer.startSpan();
    Tracer.endSpan("export-wiring-op", "OK");

    int flushed = created.getSpanExporter().flush();
    assertEquals(1, flushed);
    assertEquals(1, created.getSpanExporter().getExportedCount());
  }

  @Test
  void otlpEndpoint_createsExporterWithoutSending() {
    created = WeaverGirl.create();
    Map<String, String> config = new HashMap<>();
    // Unroutable endpoint: exporter is created but nothing is sent
    // until a batch flushes, so construction must not throw or block.
    config.put("otlpEndpoint", "http://127.0.0.1:1/v1/traces");
    config.put("otlpHeaders", "Authorization=Bearer test-token;X-Tenant=acme");
    created.initSpanExport(config);

    assertNotNull(created.getSpanExporter());
    assertTrue(created.getSpanExporter().isRunning());
    assertEquals(1, created.getSpanExporter().getFormatterCount());
  }

  @Test
  void invalidTuningValues_fallBackToDefaults() {
    created = WeaverGirl.create();
    Map<String, String> config = new HashMap<>();
    config.put("spanExport", "true");
    config.put("spanExportBatchSize", "not-a-number");
    config.put("spanExportIntervalMs", "-5");
    config.put("spanExportBufferSize", "0");
    created.initSpanExport(config);

    assertNotNull(created.getSpanExporter());
    assertEquals(100, created.getSpanExporter().getBatchSize());
    assertEquals(5_000L, created.getSpanExporter().getExportIntervalMs());
    assertEquals(10_000, created.getSpanExporter().getBufferSize());
  }

  @Test
  void customTuningValues_applied() {
    created = WeaverGirl.create();
    Map<String, String> config = new HashMap<>();
    config.put("spanExport", "true");
    config.put("spanExportBatchSize", "10");
    config.put("spanExportIntervalMs", "1000");
    config.put("spanExportBufferSize", "500");
    created.initSpanExport(config);

    assertNotNull(created.getSpanExporter());
    assertEquals(10, created.getSpanExporter().getBatchSize());
    assertEquals(1_000L, created.getSpanExporter().getExportIntervalMs());
    assertEquals(500, created.getSpanExporter().getBufferSize());
  }

  @Test
  void stopSpanExport_detachesAndStops() {
    created = WeaverGirl.create();
    Map<String, String> config = new HashMap<>();
    config.put("spanExport", "true");
    created.initSpanExport(config);
    assertNotNull(created.getSpanExporter());

    created.stopSpanExport();
    assertNull(created.getSpanExporter());

    // Previously submitted spans must no longer flow: listener detached.
    Tracer.startSpan();
    Tracer.endSpan("after-stop-op", "OK");
    // No exporter to flush — just verify no exception and nothing stuck.
  }
}
