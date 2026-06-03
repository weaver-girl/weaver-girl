package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.config.ConfigChangeEvent;
import com.github.cc11001100.weavergirl.api.config.ConfigChangeListener;
import com.github.cc11001100.weavergirl.api.config.ConfigSnapshot;
import com.github.cc11001100.weavergirl.api.config.DynamicConfigManager;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Dynamic Configuration Center (P46).
 */
class DynamicConfigManagerTest {

    private DefaultDynamicConfigManager manager;

    @BeforeEach
    void setUp() {
        manager = new DefaultDynamicConfigManager();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    // ===== Read/Write operations =====

    @Test
    void getAndSet_basicOperations() {
        assertNull(manager.get("test.key"));
        assertEquals("default", manager.get("test.key", "default"));

        manager.set("test.key", "value1", "test");
        assertEquals("value1", manager.get("test.key"));
        assertEquals("value1", manager.get("test.key", "default"));
    }

    @Test
    void remove_deletesKey() {
        manager.set("to.remove", "value", "test");
        assertNotNull(manager.get("to.remove"));

        manager.remove("to.remove", "test");
        assertNull(manager.get("to.remove"));
    }

    @Test
    void setAll_bulkUpdate() {
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("key1", "val1");
        updates.put("key2", "val2");
        updates.put("key3", "val3");

        manager.setAll(updates, "bulk-test");

        assertEquals("val1", manager.get("key1"));
        assertEquals("val2", manager.get("key2"));
        assertEquals("val3", manager.get("key3"));
    }

    @Test
    void getAll_returnsImmutableCopy() {
        manager.set("k", "v", "test");
        Map<String, String> all = manager.getAll();
        assertEquals(1, all.size());
        assertThrows(UnsupportedOperationException.class, () -> all.put("new", "val"));
    }

    @Test
    void set_rejectsNullOrEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> manager.set(null, "v", "s"));
        assertThrows(IllegalArgumentException.class, () -> manager.set("", "v", "s"));
    }

    @Test
    void setAll_rejectsNullMap() {
        assertThrows(IllegalArgumentException.class, () -> manager.setAll(null, "s"));
    }

    @Test
    void set_sameValue_noEventFired() {
        List<ConfigChangeEvent> events = new ArrayList<>();
        manager.addListener(events::add);

        manager.set("key", "same", "test");
        assertEquals(1, events.size());

        manager.set("key", "same", "test"); // same value, should not fire
        assertEquals(1, events.size()); // still 1
    }

    // ===== Change listeners =====

    @Test
    void listener_globalReceivesAllChanges() {
        List<ConfigChangeEvent> events = new ArrayList<>();
        manager.addListener(events::add);

        manager.set("a", "1", "test");
        manager.set("b", "2", "test");
        manager.remove("a", "test");

        assertEquals(3, events.size());
        assertEquals("a", events.get(0).getKey());
        assertEquals("1", events.get(0).getNewValue());
        assertNull(events.get(0).getOldValue()); // creation

        assertEquals("b", events.get(1).getKey());

        assertEquals("a", events.get(2).getKey());
        assertNull(events.get(2).getNewValue()); // removal
        assertEquals("1", events.get(2).getOldValue());
    }

    @Test
    void listener_keySpecificReceivesOnlyMatchingKey() {
        List<ConfigChangeEvent> events = new ArrayList<>();
        manager.addListener("sampling.rate", events::add);

        manager.set("sampling.rate", "50", "test");
        manager.set("other.key", "value", "test"); // should NOT trigger
        manager.set("sampling.rate", "100", "test");

        assertEquals(2, events.size());
        assertEquals("50", events.get(0).getNewValue());
        assertEquals("100", events.get(1).getNewValue());
    }

    @Test
    void listener_removeStopsNotifications() {
        List<ConfigChangeEvent> events = new ArrayList<>();
        ConfigChangeListener listener = events::add;

        manager.addListener(listener);
        manager.set("a", "1", "test");
        assertEquals(1, events.size());

        manager.removeListener(listener);
        manager.set("b", "2", "test");
        assertEquals(1, events.size()); // still 1
    }

    @Test
    void listener_exceptionDoesNotBlockOthers() {
        List<ConfigChangeEvent> events = new ArrayList<>();

        // First listener throws
        manager.addListener(e -> { throw new RuntimeException("boom"); });
        // Second listener should still be called
        manager.addListener(events::add);

        manager.set("key", "val", "test");
        assertEquals(1, events.size()); // second listener still received it
    }

    // ===== Snapshots & Rollback =====

    @Test
    void snapshot_createsVersionedCopy() {
        manager.set("k1", "v1", "test");
        manager.set("k2", "v2", "test");

        ConfigSnapshot snap = manager.snapshot("test-snapshot");
        assertEquals(1, snap.getVersion());
        assertEquals("test-snapshot", snap.getDescription());
        assertEquals(2, snap.size());
        assertEquals("v1", snap.get("k1"));
        assertEquals("v2", snap.get("k2"));
    }

    @Test
    void snapshot_isImmutable() {
        manager.set("k", "v", "test");
        ConfigSnapshot snap = manager.snapshot("test");

        assertThrows(UnsupportedOperationException.class,
                () -> snap.getConfig().put("new", "val"));
    }

    @Test
    void rollback_restoresSnapshotState() {
        manager.set("a", "1", "test");
        manager.set("b", "2", "test");
        manager.snapshot("initial");

        // Modify config
        manager.set("a", "changed", "test");
        manager.set("c", "new", "test");
        manager.remove("b", "test");
        manager.snapshot("after-changes");

        // Rollback
        boolean success = manager.rollback(1);
        assertTrue(success);

        assertEquals("1", manager.get("a"));
        assertEquals("2", manager.get("b"));
        assertNull(manager.get("c")); // was not in snapshot 1
    }

    @Test
    void rollbackLast_revertsToPreviousSnapshot() {
        manager.set("x", "10", "test");
        manager.snapshot("snap1");

        manager.set("x", "20", "test");
        manager.snapshot("snap2");

        assertTrue(manager.rollbackLast());
        assertEquals("10", manager.get("x"));
    }

    @Test
    void rollback_failsForNonexistentVersion() {
        assertFalse(manager.rollback(999));
    }

    @Test
    void rollbackLast_failsWithFewerThanTwoSnapshots() {
        assertFalse(manager.rollbackLast());

        manager.snapshot("only-one");
        assertFalse(manager.rollbackLast());
    }

    @Test
    void rollback_firesChangeEvents() {
        List<ConfigChangeEvent> events = new ArrayList<>();
        manager.addListener(events::add);

        manager.set("key", "original", "test");
        manager.snapshot("v1");
        events.clear();

        manager.set("key", "modified", "test");
        manager.snapshot("v2");
        events.clear();

        manager.rollback(1);

        // Should fire event for the rollback change
        boolean foundRollbackEvent = false;
        for (ConfigChangeEvent e : events) {
            if ("key".equals(e.getKey()) && "original".equals(e.getNewValue())) {
                foundRollbackEvent = true;
                assertTrue(e.getSource().startsWith("rollback-to-v"));
            }
        }
        assertTrue(foundRollbackEvent, "Rollback should fire change event");
    }

    @Test
    void getSnapshots_returnsAllSnapshots() {
        manager.snapshot("first");
        manager.snapshot("second");
        manager.snapshot("third");

        List<ConfigSnapshot> snapshots = manager.getSnapshots();
        assertEquals(3, snapshots.size());
        assertEquals("first", snapshots.get(0).getDescription());
        assertEquals("third", snapshots.get(2).getDescription());
    }

    // ===== Audit log =====

    @Test
    void auditLog_tracksAllChanges() {
        manager.set("k1", "v1", "source-a");
        manager.set("k2", "v2", "source-b");
        manager.set("k1", "v1-updated", "source-b");
        manager.remove("k1", "source-c");

        List<ConfigChangeEvent> audit = manager.getAuditLog();
        assertEquals(4, audit.size());

        assertEquals("k1", audit.get(0).getKey());
        assertEquals("source-a", audit.get(0).getSource());
        assertTrue(audit.get(0).isCreation()); // first time k1 is set

        assertEquals("k2", audit.get(1).getKey());
        assertTrue(audit.get(1).isCreation()); // first time k2 is set

        assertEquals("k1", audit.get(2).getKey());
        assertFalse(audit.get(2).isCreation()); // update, not creation
        assertFalse(audit.get(2).isRemoval());
        assertEquals("v1", audit.get(2).getOldValue());
        assertEquals("v1-updated", audit.get(2).getNewValue());

        assertEquals("k1", audit.get(3).getKey());
        assertTrue(audit.get(3).isRemoval());
    }

    @Test
    void auditLog_filteredByKey() {
        manager.set("target", "v1", "test");
        manager.set("other", "v2", "test");
        manager.set("target", "v3", "test");

        List<ConfigChangeEvent> filtered = manager.getAuditLog("target");
        assertEquals(2, filtered.size());
        assertEquals("v1", filtered.get(0).getNewValue());
        assertEquals("v3", filtered.get(1).getNewValue());
    }

    @Test
    void auditLog_isImmutable() {
        manager.set("k", "v", "test");
        List<ConfigChangeEvent> audit = manager.getAuditLog();
        assertThrows(UnsupportedOperationException.class, () -> audit.add(null));
    }

    @Test
    void auditLog_trimsAtMaxSize() {
        // Set MAX_AUDIT_ENTRIES + some extra
        for (int i = 0; i < 1050; i++) {
            manager.set("key" + i, "val" + i, "test");
        }
        List<ConfigChangeEvent> audit = manager.getAuditLog();
        assertTrue(audit.size() <= 1000, "Audit log should be trimmed to max size");
    }

    // ===== loadFromSource =====

    @Test
    void loadFromSource_mergesConfig() {
        manager.set("existing", "old", "test");

        Map<String, String> source = new LinkedHashMap<>();
        source.put("new", "from-source");
        source.put("existing", "updated");

        manager.loadFromSource(source, "yaml-file");

        assertEquals("updated", manager.get("existing"));
        assertEquals("from-source", manager.get("new"));
    }

    @Test
    void loadFromSource_nullMapIgnored() {
        assertDoesNotThrow(() -> manager.loadFromSource(null, "test"));
    }

    // ===== Lifecycle =====

    @Test
    void shutdown_preventsFurtherOperations() {
        manager.shutdown();
        assertThrows(IllegalStateException.class, () -> manager.set("k", "v", "test"));
        assertThrows(IllegalStateException.class, () -> manager.remove("k", "test"));
        assertThrows(IllegalStateException.class, () -> manager.snapshot("test"));
    }

    // ===== ConfigChangeEvent =====

    @Test
    void event_isCreationAndRemoval() {
        ConfigChangeEvent create = new ConfigChangeEvent("k", null, "v", "test");
        assertTrue(create.isCreation());
        assertFalse(create.isRemoval());

        ConfigChangeEvent update = new ConfigChangeEvent("k", "old", "new", "test");
        assertFalse(update.isCreation());
        assertFalse(update.isRemoval());

        ConfigChangeEvent remove = new ConfigChangeEvent("k", "v", null, "test");
        assertFalse(remove.isCreation());
        assertTrue(remove.isRemoval());
    }

    @Test
    void event_timestampIsSet() {
        long before = System.currentTimeMillis();
        ConfigChangeEvent event = new ConfigChangeEvent("k", "old", "new", "test");
        long after = System.currentTimeMillis();

        assertTrue(event.getTimestamp() >= before);
        assertTrue(event.getTimestamp() <= after);
    }

    // ===== Concurrency =====

    @Test
    void concurrentAccess_isThreadSafe() throws Exception {
        int threadCount = 10;
        int opsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "thread" + threadId + ".key" + i;
                        manager.set(key, "val" + i, "thread");
                        assertNotNull(manager.get(key));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "Concurrent access errors: " + errors);
    }

    @Test
    void concurrentListeners_dontLoseEvents() throws Exception {
        int eventCount = 100;
        CountDownLatch latch = new CountDownLatch(eventCount);
        List<ConfigChangeEvent> received = new CopyOnWriteArrayList<>();

        manager.addListener("test.key", event -> {
            received.add(event);
            latch.countDown();
        });

        for (int i = 0; i < eventCount; i++) {
            manager.set("test.key", String.valueOf(i), "test");
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(eventCount, received.size());
    }

    // ===== DynamicConfigManager.getInstance() =====

    @Test
    void getInstance_returnsCreatedManager() {
        // Our setUp creates a DefaultDynamicConfigManager which registers itself
        DynamicConfigManager instance = DynamicConfigManager.getInstance();
        assertNotNull(instance);
        assertSame(manager, instance);
    }
}
