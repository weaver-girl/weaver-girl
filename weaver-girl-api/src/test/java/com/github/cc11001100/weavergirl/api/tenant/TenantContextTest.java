package com.github.cc11001100.weavergirl.api.tenant;

import org.junit.jupiter.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-tenant isolation (P48).
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        TenantConfigRegistry.clear();
    }

    // ===== TenantContext =====

    @Test
    void tenantId_setAndGet() {
        assertNull(TenantContext.getTenantId());
        assertFalse(TenantContext.isSet());

        TenantContext.setTenantId("customer-123");
        assertEquals("customer-123", TenantContext.getTenantId());
        assertTrue(TenantContext.isSet());
    }

    @Test
    void tenantGroup_setAndGet() {
        assertNull(TenantContext.getTenantGroup());

        TenantContext.setTenantGroup("premium");
        assertEquals("premium", TenantContext.getTenantGroup());
    }

    @Test
    void clear_removesAllContext() {
        TenantContext.setTenantId("t1");
        TenantContext.setTenantGroup("g1");

        TenantContext.clear();

        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getTenantGroup());
        assertFalse(TenantContext.isSet());
    }

    @Test
    void captureAndRestore_preservesContext() {
        TenantContext.setTenantId("original");
        TenantContext.setTenantGroup("group-a");

        TenantSnapshot snapshot = TenantContext.capture();
        assertEquals("original", snapshot.getTenantId());
        assertEquals("group-a", snapshot.getTenantGroup());
        assertTrue(snapshot.hasTenant());

        TenantContext.clear();
        assertNull(TenantContext.getTenantId());

        TenantContext.restore(snapshot);
        assertEquals("original", TenantContext.getTenantId());
        assertEquals("group-a", TenantContext.getTenantGroup());
    }

    @Test
    void captureAndRestore_nullSnapshot() {
        assertDoesNotThrow(() -> TenantContext.restore(null));
    }

    @Test
    void capture_nullTenantId() {
        TenantSnapshot snap = TenantContext.capture();
        assertNull(snap.getTenantId());
        assertFalse(snap.hasTenant());
    }

    // ===== TenantConfig =====

    @Test
    void tenantConfig_builder() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("premium-tenant")
                .samplingRate(1)
                .maxRate(500)
                .thresholdInvocationsPerSecond(5000)
                .customProperty("retention", "30d")
                .build();

        assertEquals("premium-tenant", config.getTenantId());
        assertEquals(1, config.getSamplingRate());
        assertEquals(500, config.getMaxRate());
        assertEquals(5000, config.getThresholdInvocationsPerSecond());
        assertEquals("30d", config.getCustomProperty("retention"));
        assertNull(config.getCustomProperty("nonexistent"));
        assertEquals("default", config.getCustomProperty("nonexistent", "default"));
    }

    @Test
    void tenantConfig_defaultValues() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test")
                .build();

        assertEquals(1, config.getSamplingRate());
        assertEquals(100, config.getMaxRate());
        assertEquals(10000, config.getThresholdInvocationsPerSecond());
        assertTrue(config.getCustomProperties().isEmpty());
    }

    @Test
    void tenantConfig_rejectsNullTenantId() {
        assertThrows(IllegalArgumentException.class, () ->
                TenantConfig.builder().build());
        assertThrows(IllegalArgumentException.class, () ->
                TenantConfig.builder().tenantId("").build());
    }

    @Test
    void tenantConfig_customPropertiesAreImmutable() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test")
                .customProperty("key", "val")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> config.getCustomProperties().put("new", "val"));
    }

    // ===== TenantConfigRegistry =====

    @Test
    void registry_registerAndGet() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("tenant-1")
                .samplingRate(10)
                .build();

        TenantConfigRegistry.register(config);

        TenantConfig retrieved = TenantConfigRegistry.get("tenant-1");
        assertNotNull(retrieved);
        assertEquals(10, retrieved.getSamplingRate());
        assertNull(TenantConfigRegistry.get("nonexistent"));
    }

    @Test
    void registry_getCurrent() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("current-tenant")
                .samplingRate(5)
                .build();
        TenantConfigRegistry.register(config);

        // No tenant set
        assertNull(TenantConfigRegistry.getCurrent());

        // Set tenant
        TenantContext.setTenantId("current-tenant");
        TenantConfig current = TenantConfigRegistry.getCurrent();
        assertNotNull(current);
        assertEquals(5, current.getSamplingRate());
    }

    @Test
    void registry_unregister() {
        TenantConfig config = TenantConfig.builder().tenantId("to-remove").build();
        TenantConfigRegistry.register(config);
        assertEquals(1, TenantConfigRegistry.size());

        TenantConfig removed = TenantConfigRegistry.unregister("to-remove");
        assertNotNull(removed);
        assertEquals(0, TenantConfigRegistry.size());
        assertNull(TenantConfigRegistry.get("to-remove"));
    }

    @Test
    void registry_registeredTenantIds() {
        TenantConfigRegistry.register(TenantConfig.builder().tenantId("a").build());
        TenantConfigRegistry.register(TenantConfig.builder().tenantId("b").build());

        Set<String> ids = TenantConfigRegistry.getRegisteredTenantIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
    }

    @Test
    void registry_registerNullIgnored() {
        assertDoesNotThrow(() -> TenantConfigRegistry.register(null));
        assertEquals(0, TenantConfigRegistry.size());
    }

    @Test
    void registry_getNullReturnsNull() {
        assertNull(TenantConfigRegistry.get(null));
        assertNull(TenantConfigRegistry.unregister(null));
    }

    // ===== Cross-thread propagation =====

    @Test
    void tenantContext_threadIndependent() throws Exception {
        TenantContext.setTenantId("main-thread");

        Thread t = new Thread(() -> {
            // ThreadLocal: child should NOT see parent's tenant
            assertNull(TenantContext.getTenantId());
            TenantContext.setTenantId("child-thread");
            assertEquals("child-thread", TenantContext.getTenantId());
        });
        t.start();
        t.join(5000);

        // Main thread still has its own value
        assertEquals("main-thread", TenantContext.getTenantId());
    }

    @Test
    void tenantContext_crossThreadRestore() throws Exception {
        TenantContext.setTenantId("parent");
        TenantSnapshot snapshot = TenantContext.capture();

        Thread t = new Thread(() -> {
            assertNull(TenantContext.getTenantId());
            TenantContext.restore(snapshot);
            assertEquals("parent", TenantContext.getTenantId());
        });
        t.start();
        t.join(5000);
    }
}
