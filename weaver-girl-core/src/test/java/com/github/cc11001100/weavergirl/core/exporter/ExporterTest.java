package com.github.cc11001100.weavergirl.core.exporter;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DataExporter SPI and built-in exporters (P56).
 */
class ExporterTest {

    @AfterEach
    void tearDown() {
        ExporterRegistry.shutdownAll();
    }

    // ===== DataExporter interface =====

    @Test
    void dataExporter_defaultMethods() {
        DataExporter exporter = new DataExporter() {
            @Override public String name() { return "test-default"; }
            @Override public void export(InterceptorEvent event) {}
        };
        assertDoesNotThrow(() -> exporter.flush());
        assertDoesNotThrow(() -> exporter.init(null));
        assertDoesNotThrow(() -> exporter.shutdown());
        assertTrue(exporter.isHealthy());
    }

    // ===== ExporterRegistry =====

    @Test
    void registry_registerAndActivate() {
        DataExporter exporter = new InMemoryExporter();
        ExporterRegistry.register(exporter);

        assertTrue(ExporterRegistry.getExporterNames().contains("in-memory"));
        assertNotNull(ExporterRegistry.get("in-memory"));

        assertTrue(ExporterRegistry.activate("in-memory"));
        assertTrue(ExporterRegistry.getActiveExporterNames().contains("in-memory"));
    }

    @Test
    void registry_activateUnknownReturnsFalse() {
        assertFalse(ExporterRegistry.activate("nonexistent"));
    }

    @Test
    void registry_deactivate() {
        ExporterRegistry.register(new InMemoryExporter());
        ExporterRegistry.activate("in-memory");
        ExporterRegistry.deactivate("in-memory");
        assertFalse(ExporterRegistry.getActiveExporterNames().contains("in-memory"));
    }

    @Test
    void registry_unregister() {
        ExporterRegistry.register(new InMemoryExporter());
        ExporterRegistry.activate("in-memory");
        ExporterRegistry.unregister("in-memory");
        assertFalse(ExporterRegistry.getExporterNames().contains("in-memory"));
    }

    @Test
    void registry_exportEvent_deliversToActiveExporters() {
        InMemoryExporter exporter = new InMemoryExporter();
        ExporterRegistry.register(exporter);
        ExporterRegistry.activate("in-memory");

        InterceptorEvent event = InterceptorEvent.builder()
                .type("test").plugin("p").build();
        ExporterRegistry.exportEvent(event);

        assertEquals(1, exporter.size());
    }

    @Test
    void registry_exportEvent_skipsInactiveExporters() {
        InMemoryExporter exporter = new InMemoryExporter();
        ExporterRegistry.register(exporter);
        // NOT activating

        ExporterRegistry.exportEvent(InterceptorEvent.builder()
                .type("test").plugin("p").build());
        assertEquals(0, exporter.size());
    }

    @Test
    void registry_exportEvent_nullIgnored() {
        assertDoesNotThrow(() -> ExporterRegistry.exportEvent(null));
    }

    @Test
    void registry_healthStatus() {
        ExporterRegistry.register(new InMemoryExporter());
        ExporterRegistry.activate("in-memory");

        Map<String, Boolean> health = ExporterRegistry.getHealthStatus();
        assertTrue(health.get("in-memory"));
    }

    @Test
    void registry_flushAll() {
        ExporterRegistry.register(new InMemoryExporter());
        ExporterRegistry.activate("in-memory");
        assertDoesNotThrow(() -> ExporterRegistry.flushAll());
    }

    @Test
    void registry_initExporter() {
        InMemoryExporter exporter = new InMemoryExporter();
        ExporterRegistry.register(exporter);
        Map<String, String> config = new HashMap<>();
        config.put("maxEvents", "50");
        ExporterRegistry.initExporter("in-memory", config);
        // Verify init was applied (maxEvents = 50)
        for (int i = 0; i < 60; i++) {
            exporter.export(InterceptorEvent.builder().type("t").plugin("p").build());
        }
        assertTrue(exporter.size() <= 50);
    }

    // ===== InMemoryExporter =====

    @Test
    void inMemory_storesAndRetrievesEvents() {
        InMemoryExporter exporter = new InMemoryExporter();
        exporter.export(InterceptorEvent.builder().type("slow").plugin("jdbc").build());
        exporter.export(InterceptorEvent.builder().type("fast").plugin("http").build());

        assertEquals(2, exporter.size());
        assertEquals("slow", exporter.getEvents().get(0).getType());
    }

    @Test
    void inMemory_getRecent() {
        InMemoryExporter exporter = new InMemoryExporter();
        for (int i = 0; i < 10; i++) {
            exporter.export(InterceptorEvent.builder().type("t" + i).plugin("p").build());
        }
        List<InterceptorEvent> recent = exporter.getRecentEvents(3);
        assertEquals(3, recent.size());
        assertEquals("t7", recent.get(0).getType());
    }

    @Test
    void inMemory_getByType() {
        InMemoryExporter exporter = new InMemoryExporter();
        exporter.export(InterceptorEvent.builder().type("slow").plugin("p").build());
        exporter.export(InterceptorEvent.builder().type("fast").plugin("p").build());
        exporter.export(InterceptorEvent.builder().type("slow").plugin("p").build());

        assertEquals(2, exporter.getEventsByType("slow").size());
    }

    @Test
    void inMemory_boundedSize() {
        InMemoryExporter exporter = new InMemoryExporter();
        exporter.init(Collections.singletonMap("maxEvents", "5"));
        for (int i = 0; i < 10; i++) {
            exporter.export(InterceptorEvent.builder().type("t").plugin("p").build());
        }
        assertTrue(exporter.size() <= 5);
    }

    @Test
    void inMemory_clear() {
        InMemoryExporter exporter = new InMemoryExporter();
        exporter.export(InterceptorEvent.builder().type("t").plugin("p").build());
        exporter.clear();
        assertEquals(0, exporter.size());
    }

    // ===== LoggingExporter =====

    @Test
    void logging_exportsEvent() {
        LoggingExporter exporter = new LoggingExporter();
        InterceptorEvent event = InterceptorEvent.builder()
                .type("test-type")
                .plugin("test-plugin")
                .className("com.example.Service")
                .methodName("execute")
                .durationMs(1500)
                .attribute("sql", "SELECT 1")
                .build();
        // Should not throw
        assertDoesNotThrow(() -> exporter.export(event));
        assertEquals(1, exporter.getExportCount());
    }

    @Test
    void logging_configDisableAttributes() {
        LoggingExporter exporter = new LoggingExporter();
        exporter.init(Collections.singletonMap("includeAttributes", "false"));

        InterceptorEvent event = InterceptorEvent.builder()
                .type("t").plugin("p")
                .attribute("key", "value")
                .build();
        assertDoesNotThrow(() -> exporter.export(event));
    }

    @Test
    void logging_healthy() {
        assertTrue(new LoggingExporter().isHealthy());
    }
}
