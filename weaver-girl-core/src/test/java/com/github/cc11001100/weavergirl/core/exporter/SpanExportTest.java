package com.github.cc11001100.weavergirl.core.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.testsupport.Statics;
import com.github.cc11001100.weavergirl.core.trace.TracerSpanExporterBridge;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Wiring span export from configuration. */
class SpanExportTest {

  @AfterEach
  void detach() throws Exception {
    java.lang.reflect.Field field = Tracer.class.getDeclaredField("completionListeners");
    Object composite = Statics.read(field, null);
    java.lang.reflect.Method listeners = composite.getClass().getMethod("getListeners");
    for (Object listener : new java.util.ArrayList<>((java.util.List<?>) listeners.invoke(composite))) {
      Tracer.removeCompletionListener((SpanCompletionListener) listener);
    }
  }

  /** Nothing is configured, so nothing is started and there is nothing to stop. */
  @Test
  void nothingConfiguredStartsNothing() {
    SpanExport export = new SpanExport();
    assertFalse(export.start(null));
    assertFalse(export.start(new HashMap<>()));
    assertNull(export.exporter());
    assertDoesNotThrow(export::stop);
  }

  /** Logging-only export starts an exporter, and stopping it detaches the bridge. */
  @Test
  void logOnlyExportStartsAndStops() {
    SpanExport export = new SpanExport();
    Map<String, String> config = new HashMap<>();
    config.put("spanExport", "true");

    assertTrue(export.start(config));
    assertNotNull(export.exporter());
    assertTrue(export.exporter().isRunning());

    export.stop();
    assertNull(export.exporter());
  }

  /** A header string is split into name and value pairs, and a pair without an equals sign is dropped. */
  @Test
  void anEndpointWithHeadersStartsAnExporter() {
    SpanExport export = new SpanExport();
    Map<String, String> config = new HashMap<>();
    config.put("otlpEndpoint", "http://127.0.0.1:1");
    config.put("otlpHeaders", "Authorization=Bearer x; malformed; X-Token=abc");
    config.put("spanExportBatchSize", "50");
    config.put("spanExportIntervalMs", "1000");
    config.put("spanExportBufferSize", "500");

    try {
      assertTrue(export.start(config));
      assertEquals(50, export.exporter().getBatchSize());
      assertEquals(500, export.exporter().getBufferSize());
    } finally {
      export.stop();
    }
  }

  /** A size that is not a positive number falls back to the default. */
  @Test
  void unusableSizesFallBackToDefaults() {
    assertEquals(100, SpanExport.parsePositiveInt(null, 100));
    assertEquals(100, SpanExport.parsePositiveInt("  ", 100));
    assertEquals(100, SpanExport.parsePositiveInt("0", 100));
    assertEquals(100, SpanExport.parsePositiveInt("-3", 100));
    assertEquals(7, SpanExport.parsePositiveInt(" 7 ", 100));
    assertEquals(5L, SpanExport.parsePositiveLong(null, 5L));
    assertEquals(5L, SpanExport.parsePositiveLong("nope", 5L));
    assertEquals(5L, SpanExport.parsePositiveLong("0", 5L));
    assertEquals(9L, SpanExport.parsePositiveLong(" 9 ", 5L));
  }

  /** A bridge that cannot be detached is logged, and the exporter is still stopped. */
  @Test
  void aBridgeThatCannotBeDetachedDoesNotStopShutdown() throws Exception {
    SpanExport export = new SpanExport();
    Map<String, String> config = new HashMap<>();
    config.put("spanExport", "TRUE");
    assertTrue(export.start(config));

    java.lang.reflect.Field bridge = SpanExport.class.getDeclaredField("bridge");
    TracerSpanExporterBridge installed = (TracerSpanExporterBridge) Statics.read(bridge, export);
    // A subclass so the listener list accepts it, and one whose removal fails outright.
    TracerSpanExporterBridge failing =
        new TracerSpanExporterBridge(null) {
          @Override
          public boolean equals(Object other) {
            throw new IllegalStateException("cannot detach");
          }
        };
    Statics.write(bridge, export, failing);
    try {
      assertDoesNotThrow(export::stop);
      assertNull(export.exporter(), "the exporter was left running");
    } finally {
      Tracer.removeCompletionListener(installed);
    }
  }

  /** An exporter that throws while it stops is logged, and the export is still torn down. */
  @Test
  void anExporterThatFailsToStopIsSwallowed() throws Exception {
    SpanExport export = new SpanExport();
    java.lang.reflect.Field exporter = SpanExport.class.getDeclaredField("exporter");
    exporter.setAccessible(true);
    exporter.set(
        export,
        new SpanExporter() {
          @Override
          public void stop() {
            throw new IllegalStateException("stop failed");
          }
        });

    assertDoesNotThrow(export::stop);
    assertNull(export.exporter(), "a failing exporter was left in place");
  }
}
