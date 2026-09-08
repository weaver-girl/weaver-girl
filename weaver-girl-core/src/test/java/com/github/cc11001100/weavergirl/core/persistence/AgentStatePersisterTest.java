package com.github.cc11001100.weavergirl.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentStatePersisterTest {

  @TempDir Path tempDir;

  private Path stateDir;
  private AgentStatePersister persister;

  @BeforeEach
  void setUp() {
    stateDir = tempDir.resolve("persister-test");
    AgentStatePersister.StateProvider provider =
        () -> new AgentStateSnapshot().setInterceptorCount(10).setTotalInvocationCount(100);
    persister = new AgentStatePersister(provider, stateDir, 1000);
  }

  @AfterEach
  void tearDown() {
    if (persister.isRunning()) {
      persister.stop();
    }
  }

  @Test
  void constructor_setsDefaults() {
    AgentStatePersister.StateProvider provider = () -> new AgentStateSnapshot();
    AgentStatePersister p = new AgentStatePersister(provider);
    assertEquals(60_000, p.getIntervalMs());
    assertFalse(p.isRunning());
  }

  @Test
  void start_beginsScheduling() {
    persister.start();
    assertTrue(persister.isRunning());
  }

  @Test
  void start_idempotent() {
    persister.start();
    persister.start(); // second call should be no-op
    assertTrue(persister.isRunning());
  }

  @Test
  void stop_stopsScheduling() {
    persister.start();
    persister.stop();
    assertFalse(persister.isRunning());
  }

  @Test
  void stop_idempotent() {
    persister.start();
    persister.stop();
    persister.stop(); // second call should be no-op
    assertFalse(persister.isRunning());
  }

  @Test
  void takeSnapshot_savesToFile() {
    AgentStateSnapshot snapshot = persister.takeSnapshot();
    assertNotNull(snapshot);
    assertEquals(10, snapshot.getInterceptorCount());
    assertEquals(100, snapshot.getTotalInvocationCount());
    assertTrue(AgentStateSnapshot.exists(stateDir));
  }

  @Test
  void takeSnapshot_updatesLastSnapshotTime() {
    assertEquals(0, persister.getLastSnapshotTimeMs());
    persister.takeSnapshot();
    assertTrue(persister.getLastSnapshotTimeMs() > 0);
  }

  @Test
  void restore_loadsLastSnapshot() {
    persister.takeSnapshot();
    AgentStateSnapshot restored = persister.restore();
    assertNotNull(restored);
    assertEquals(10, restored.getInterceptorCount());
  }

  @Test
  void restore_noSnapshot_returnsNull() {
    AgentStateSnapshot restored = persister.restore();
    assertNull(restored);
  }

  @Test
  void clearState_removesSnapshot() {
    persister.takeSnapshot();
    assertTrue(AgentStateSnapshot.exists(stateDir));
    assertTrue(persister.clearState());
    assertFalse(AgentStateSnapshot.exists(stateDir));
  }

  @Test
  void listener_onSnapshotCalled() {
    AtomicReference<AgentStateSnapshot> captured = new AtomicReference<>();
    persister.addListener(
        new AgentStatePersister.SnapshotListener() {
          @Override
          public void onSnapshot(AgentStateSnapshot snapshot) {
            captured.set(snapshot);
          }
        });

    persister.takeSnapshot();
    assertNotNull(captured.get());
    assertEquals(10, captured.get().getInterceptorCount());
  }

  @Test
  void listener_onRestoreCalled() {
    persister.takeSnapshot();

    AtomicReference<AgentStateSnapshot> captured = new AtomicReference<>();
    persister.addListener(
        new AgentStatePersister.SnapshotListener() {
          @Override
          public void onRestore(AgentStateSnapshot snapshot) {
            captured.set(snapshot);
          }
        });

    persister.restore();
    assertNotNull(captured.get());
  }

  @Test
  void removeListener_stopsReceivingEvents() {
    AtomicBoolean called = new AtomicBoolean(false);
    AgentStatePersister.SnapshotListener listener =
        new AgentStatePersister.SnapshotListener() {
          @Override
          public void onSnapshot(AgentStateSnapshot snapshot) {
            called.set(true);
          }
        };

    persister.addListener(listener);
    persister.takeSnapshot();
    assertTrue(called.get());

    called.set(false);
    persister.removeListener(listener);
    persister.takeSnapshot();
    assertFalse(called.get());
  }

  @Test
  void snapshotAndRestore_roundtrip() {
    // Take a snapshot
    persister.takeSnapshot();

    // Restore it
    AgentStateSnapshot restored = persister.restore();
    assertNotNull(restored);
    assertEquals(10, restored.getInterceptorCount());
    assertEquals(100, restored.getTotalInvocationCount());
  }

  @Test
  void stop_takesFinalSnapshot() {
    persister.start();
    persister.stop();
    // After stop, a final snapshot should have been taken
    assertTrue(AgentStateSnapshot.exists(stateDir));
  }

  @Test
  void getSnapshotDir_returnsConfiguredDir() {
    assertEquals(stateDir, persister.getSnapshotDir());
  }

  @Test
  void getIntervalMs_returnsConfiguredInterval() {
    assertEquals(1000, persister.getIntervalMs());
  }

  @Test
  void constructor_zeroInterval_usesDefault() {
    AgentStatePersister.StateProvider provider = () -> new AgentStateSnapshot();
    AgentStatePersister p = new AgentStatePersister(provider, stateDir, 0);
    assertEquals(60_000, p.getIntervalMs());
  }

  @Test
  void constructor_negativeInterval_usesDefault() {
    AgentStatePersister.StateProvider provider = () -> new AgentStateSnapshot();
    AgentStatePersister p = new AgentStatePersister(provider, stateDir, -1);
    assertEquals(60_000, p.getIntervalMs());
  }
}
