package com.github.cc11001100.weavergirl.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.core.testsupport.Statics;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

  /** A scheduler that is still running when the wait expires is shut down forcibly. */
  @Test
  void stopForcesASchedulerThatWillNotFinish() throws Exception {
    java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
    ScheduledExecutorService stubborn =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
          }

          @Override
          public java.util.List<Runnable> shutdownNow() {
            forced.incrementAndGet();
            return super.shutdownNow();
          }
        };
    withScheduler(
        stubborn,
        () -> {
          persister.start();
          persister.stop();
        });
    assertEquals(1, forced.get(), "a scheduler that ignored the wait was not forced to stop");
  }

  /** A snapshot file that cannot be read restores as nothing rather than failing. */
  @Test
  void anUnreadableSnapshotRestoresAsNothing() throws IOException {
    java.nio.file.Files.createDirectories(stateDir);
    // A directory where the snapshot file should be cannot be opened as a file.
    java.nio.file.Path obstacle = stateDir.resolve("agent-state.properties");
    java.nio.file.Files.createDirectory(obstacle);
    java.nio.file.Files.write(obstacle.resolve("held"), new byte[] {1});

    assertNull(persister.restore());
  }

  /** A final snapshot that throws is logged, and stopping still completes. */
  @Test
  void aFinalSnapshotThatThrowsDoesNotStopShutdown() throws Exception {
    AgentStatePersister failing =
        new AgentStatePersister(
            () -> {
              throw new IllegalStateException("state unavailable");
            },
            stateDir,
            1000);
    try {
      failing.start();
      assertDoesNotThrow(failing::stop);
    } finally {
      if (failing.isRunning()) {
        failing.stop();
      }
    }
  }

  /** An interrupted wait while stopping forces the scheduler down and restores the interrupt. */
  @Test
  void anInterruptedStopRestoresTheInterrupt() throws Exception {
    ScheduledExecutorService blocking =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("stopping");
          }
        };
    Thread.interrupted();
    try {
      withScheduler(
          blocking,
          () -> {
            persister.start();
            persister.stop();
          });
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");
    } finally {
      Thread.interrupted();
    }
  }

  /** Runs {@code body} with the persister's scheduler replaced, and puts the original back after. */
  private void withScheduler(ScheduledExecutorService replacement, Runnable body) throws Exception {
    java.lang.reflect.Field field = AgentStatePersister.class.getDeclaredField("scheduler");
    ScheduledExecutorService original = (ScheduledExecutorService) Statics.read(field, persister);
    Statics.write(field, persister, replacement);
    try {
      body.run();
    } finally {
      Statics.write(field, persister, original);
      original.shutdownNow();
    }
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

  /** One listener throwing must not stop the snapshot, nor the listener after it. */
  @Test
  void aListenerThatThrowsDoesNotStopTheSnapshot() {
    persister.addListener(
        new AgentStatePersister.SnapshotListener() {
          @Override
          public void onSnapshot(AgentStateSnapshot snapshot) {
            throw new IllegalStateException("listener down");
          }

          @Override
          public void onRestore(AgentStateSnapshot snapshot) {
            throw new IllegalStateException("listener down");
          }
        });
    AtomicBoolean saw = new AtomicBoolean(false);
    persister.addListener(
        new AgentStatePersister.SnapshotListener() {
          @Override
          public void onSnapshot(AgentStateSnapshot snapshot) {
            saw.set(true);
          }

          @Override
          public void onRestore(AgentStateSnapshot snapshot) {
            saw.set(true);
          }
        });

    assertNotNull(persister.takeSnapshot(), "a broken listener aborted the snapshot");
    assertTrue(saw.get(), "the listener after the broken one was skipped");

    saw.set(false);
    assertNotNull(persister.restore());
    assertTrue(saw.get(), "a broken listener aborted the restore");
  }

  /** A snapshot directory that cannot be written to fails the snapshot instead of throwing. */
  @Test
  void takeSnapshot_returnsNullWhenTheDirectoryCannotBeWritten() throws Exception {
    AgentStatePersister.StateProvider provider = () -> new AgentStateSnapshot();
    // A regular file where a directory should be makes creating the snapshot directory fail.
    java.nio.file.Files.write(tempDir.resolve("blocked"), new byte[0]);
    AgentStatePersister blocked =
        new AgentStatePersister(provider, tempDir.resolve("blocked").resolve("state"), 1000);

    assertNull(blocked.takeSnapshot(), "writing under a file was reported as success");
    assertNull(blocked.restore());
  }

  @Test
  void constructor_negativeInterval_usesDefault() {
    AgentStatePersister.StateProvider provider = () -> new AgentStateSnapshot();
    AgentStatePersister p = new AgentStatePersister(provider, stateDir, -1);
    assertEquals(60_000, p.getIntervalMs());
  }
}
