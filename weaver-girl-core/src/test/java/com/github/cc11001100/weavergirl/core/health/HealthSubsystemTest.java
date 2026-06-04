package com.github.cc11001100.weavergirl.core.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HealthSubsystemTest {

    private CompositeHealthIndicator registry;

    @BeforeEach
    void setUp() {
        registry = CompositeHealthIndicator.getInstance();
        // Clear any existing indicators
        for (String name : registry.getComponentNames()) {
            registry.unregister(name);
        }
    }

    @AfterEach
    void tearDown() {
        for (String name : registry.getComponentNames()) {
            registry.unregister(name);
        }
    }

    // === HealthStatus ===

    @Test
    void healthStatus_builder_defaults() {
        HealthStatus status = HealthStatus.builder().build();
        assertEquals("UP", status.getStatus());
        assertTrue(status.getDetails().isEmpty());
        assertTrue(status.isUp());
        assertFalse(status.isDown());
    }

    @Test
    void healthStatus_up() {
        HealthStatus status = HealthStatus.builder().up().build();
        assertEquals("UP", status.getStatus());
        assertTrue(status.isUp());
    }

    @Test
    void healthStatus_down() {
        HealthStatus status = HealthStatus.builder().down().build();
        assertEquals("DOWN", status.getStatus());
        assertTrue(status.isDown());
    }

    @Test
    void healthStatus_degraded() {
        HealthStatus status = HealthStatus.builder().degraded().build();
        assertEquals("DEGRADED", status.getStatus());
        assertFalse(status.isUp());
        assertFalse(status.isDown());
    }

    @Test
    void healthStatus_customStatus() {
        HealthStatus status = HealthStatus.builder().status("STARTING").build();
        assertEquals("STARTING", status.getStatus());
    }

    @Test
    void healthStatus_withDetails() {
        HealthStatus status = HealthStatus.builder()
                .up()
                .detail("heapUsedMB", 256)
                .detail("heapMaxMB", 1024)
                .detail("usagePercent", "25%")
                .build();
        assertEquals(3, status.getDetails().size());
        assertEquals(256, status.getDetails().get("heapUsedMB"));
        assertEquals("25%", status.getDetails().get("usagePercent"));
    }

    @Test
    void healthStatus_detailsAreUnmodifiable() {
        HealthStatus status = HealthStatus.builder().detail("key", "val").build();
        assertThrows(UnsupportedOperationException.class,
                () -> status.getDetails().put("new", "value"));
    }

    @Test
    void healthStatus_timestampIsSet() {
        long before = System.currentTimeMillis();
        HealthStatus status = HealthStatus.builder().build();
        long after = System.currentTimeMillis();
        assertTrue(status.getTimestamp() >= before && status.getTimestamp() <= after);
    }

    @Test
    void healthStatus_toJson_noDetails() {
        HealthStatus status = HealthStatus.builder().up().build();
        String json = status.toJson();
        assertTrue(json.contains("\"status\":\"UP\""));
    }

    @Test
    void healthStatus_toJson_withDetails() {
        HealthStatus status = HealthStatus.builder()
                .up()
                .detail("count", 42)
                .detail("name", "test")
                .build();
        String json = status.toJson();
        assertTrue(json.contains("\"status\":\"UP\""));
        assertTrue(json.contains("\"count\":42"));
        assertTrue(json.contains("\"name\":\"test\""));
    }

    @Test
    void healthStatus_toString_containsStatus() {
        HealthStatus status = HealthStatus.builder().down().build();
        assertTrue(status.toString().contains("DOWN"));
    }

    // === CompositeHealthIndicator ===

    @Test
    void getInstance_returnsSingleton() {
        assertSame(CompositeHealthIndicator.getInstance(), CompositeHealthIndicator.getInstance());
    }

    @Test
    void register_addsIndicator() {
        registry.register("test", () -> HealthStatus.builder().up().build());
        assertEquals(1, registry.size());
        assertTrue(registry.getComponentNames().contains("test"));
    }

    @Test
    void unregister_removesIndicator() {
        registry.register("test", () -> HealthStatus.builder().up().build());
        registry.unregister("test");
        assertEquals(0, registry.size());
    }

    @Test
    void check_noIndicators_returnsEmptyReport() {
        HealthReport report = registry.check();
        assertEquals("UP", report.getOverallStatus());
        assertEquals(0, report.getComponentCount());
    }

    @Test
    void check_singleUp_returnsUp() {
        registry.register("memory", () -> HealthStatus.builder().up()
                .detail("heapUsedMB", 100).build());
        HealthReport report = registry.check();
        assertEquals("UP", report.getOverallStatus());
        assertTrue(report.isHealthy());
        assertEquals(1, report.getUpCount());
        assertEquals(0, report.getDownCount());
    }

    @Test
    void check_singleDown_returnsDown() {
        registry.register("database", () -> HealthStatus.builder().down()
                .detail("error", "connection refused").build());
        HealthReport report = registry.check();
        assertEquals("DOWN", report.getOverallStatus());
        assertFalse(report.isHealthy());
        assertEquals(1, report.getDownCount());
    }

    @Test
    void check_mixedUpAndDown_overallDown() {
        registry.register("memory", () -> HealthStatus.builder().up().build());
        registry.register("database", () -> HealthStatus.builder().down()
                .detail("error", "timeout").build());
        HealthReport report = registry.check();
        assertEquals("DOWN", report.getOverallStatus());
        assertEquals(1, report.getUpCount());
        assertEquals(1, report.getDownCount());
    }

    @Test
    void check_degradedAndUp_overallDegraded() {
        registry.register("memory", () -> HealthStatus.builder().up().build());
        registry.register("disk", () -> HealthStatus.builder().degraded()
                .detail("usagePercent", "85%").build());
        HealthReport report = registry.check();
        assertEquals("DEGRADED", report.getOverallStatus());
    }

    @Test
    void check_degradedAndDown_overallDown() {
        registry.register("disk", () -> HealthStatus.builder().degraded().build());
        registry.register("database", () -> HealthStatus.builder().down().build());
        HealthReport report = registry.check();
        assertEquals("DOWN", report.getOverallStatus());
    }

    @Test
    void check_exceptionInIndicator_reportsDown() {
        registry.register("broken", () -> {
            throw new RuntimeException("check failed");
        });
        HealthReport report = registry.check();
        HealthStatus broken = report.getComponents().get("broken");
        assertEquals("DOWN", broken.getStatus());
        assertTrue(broken.getDetails().get("error").toString().contains("check failed"));
    }

    @Test
    void checkComponent_existing_returnsStatus() {
        registry.register("memory", () -> HealthStatus.builder().up()
                .detail("freeMB", 512).build());
        HealthStatus status = registry.checkComponent("memory");
        assertEquals("UP", status.getStatus());
        assertEquals(512, status.getDetails().get("freeMB"));
    }

    @Test
    void checkComponent_nonexistent_returnsUnknown() {
        HealthStatus status = registry.checkComponent("nonexistent");
        assertEquals("UNKNOWN", status.getStatus());
    }

    @Test
    void check_multipleComponents_allIncluded() {
        registry.register("memory", () -> HealthStatus.builder().up().build());
        registry.register("disk", () -> HealthStatus.builder().up().build());
        registry.register("network", () -> HealthStatus.builder().up().build());
        HealthReport report = registry.check();
        assertEquals(3, report.getComponentCount());
        assertTrue(report.getComponents().containsKey("memory"));
        assertTrue(report.getComponents().containsKey("disk"));
        assertTrue(report.getComponents().containsKey("network"));
    }

    @Test
    void check_reportHasTimestamp() {
        long before = System.currentTimeMillis();
        HealthReport report = registry.check();
        long after = System.currentTimeMillis();
        assertTrue(report.getTimestamp() >= before && report.getTimestamp() <= after);
    }

    @Test
    void check_reportHasDuration() {
        registry.register("slow", () -> {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return HealthStatus.builder().up().build();
        });
        HealthReport report = registry.check();
        assertTrue(report.getCheckDurationMs() >= 10);
    }

    @Test
    void register_overwriteReplaces() {
        registry.register("test", () -> HealthStatus.builder().up().build());
        registry.register("test", () -> HealthStatus.builder().down().build());
        assertEquals(1, registry.size());
        HealthStatus status = registry.checkComponent("test");
        assertEquals("DOWN", status.getStatus());
    }

    // === HealthReport ===

    @Test
    void healthReport_toJson_containsAllFields() {
        registry.register("mem", () -> HealthStatus.builder().up().detail("mb", 100).build());
        registry.register("db", () -> HealthStatus.builder().down().detail("err", "timeout").build());
        HealthReport report = registry.check();
        String json = report.toJson();
        assertTrue(json.contains("\"status\":\"DOWN\""));
        assertTrue(json.contains("\"mem\":"));
        assertTrue(json.contains("\"db\":"));
        assertTrue(json.contains("\"checkDurationMs\":"));
        assertTrue(json.contains("\"timestamp\":"));
    }

    @Test
    void healthReport_toString_containsSummary() {
        registry.register("test", () -> HealthStatus.builder().up().build());
        HealthReport report = registry.check();
        String str = report.toString();
        assertTrue(str.contains("UP"));
        assertTrue(str.contains("components=1"));
    }

    @Test
    void healthReport_componentsAreUnmodifiable() {
        registry.register("test", () -> HealthStatus.builder().up().build());
        HealthReport report = registry.check();
        assertThrows(UnsupportedOperationException.class,
                () -> report.getComponents().put("new", HealthStatus.builder().up().build()));
    }
}
