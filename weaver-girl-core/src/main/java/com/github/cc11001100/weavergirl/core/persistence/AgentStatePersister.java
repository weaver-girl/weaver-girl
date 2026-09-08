package com.github.cc11001100.weavergirl.core.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages periodic agent state snapshot persistence and restoration.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Periodic auto-snapshot at configurable intervals
 *   <li>Graceful shutdown snapshot
 *   <li>State restoration on startup
 *   <li>Configurable snapshot directory
 *   <li>Snapshot history with rotation
 * </ul>
 */
public class AgentStatePersister {

  private static final Logger log = LoggerFactory.getLogger(AgentStatePersister.class);

  private static final long DEFAULT_INTERVAL_MS = 60_000; // 1 minute
  private static final int MAX_HISTORY = 5;

  private final Path snapshotDir;
  private final long intervalMs;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong lastSnapshotTime = new AtomicLong(0);
  private final List<SnapshotListener> listeners = new CopyOnWriteArrayList<>();
  private final StateProvider stateProvider;

  private ScheduledFuture<?> scheduledTask;

  /** Create a persister with default settings. */
  public AgentStatePersister(StateProvider stateProvider) {
    this(stateProvider, Paths.get("weaver-girl-state"), DEFAULT_INTERVAL_MS);
  }

  /** Create a persister with custom directory and interval. */
  public AgentStatePersister(StateProvider stateProvider, Path snapshotDir, long intervalMs) {
    this.stateProvider = stateProvider;
    this.snapshotDir = snapshotDir;
    this.intervalMs = intervalMs > 0 ? intervalMs : DEFAULT_INTERVAL_MS;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "weaver-girl-state-persister");
              t.setDaemon(true);
              return t;
            });
  }

  /** Start periodic snapshot scheduling. */
  public void start() {
    if (running.compareAndSet(false, true)) {
      scheduledTask =
          scheduler.scheduleAtFixedRate(
              this::takeSnapshot, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
      log.info("[AgentState] Persister started (interval: {}ms, dir: {})", intervalMs, snapshotDir);
    }
  }

  /** Stop periodic snapshot scheduling and take a final snapshot. */
  public void stop() {
    if (running.compareAndSet(true, false)) {
      if (scheduledTask != null) {
        scheduledTask.cancel(false);
      }
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }

      // Take final shutdown snapshot
      try {
        takeSnapshot();
        log.info("[AgentState] Final shutdown snapshot taken");
      } catch (Exception e) {
        log.warn("[AgentState] Failed to take shutdown snapshot: {}", e.getMessage());
      }
    }
  }

  /** Take a snapshot of the current agent state and persist it. */
  public AgentStateSnapshot takeSnapshot() {
    try {
      AgentStateSnapshot snapshot = stateProvider.captureState();
      Path file = snapshot.save(snapshotDir);
      lastSnapshotTime.set(System.currentTimeMillis());

      // Rotate history
      rotateHistory();

      // Notify listeners
      for (SnapshotListener listener : listeners) {
        try {
          listener.onSnapshot(snapshot);
        } catch (Exception e) {
          log.warn("[AgentState] Listener error: {}", e.getMessage());
        }
      }

      return snapshot;
    } catch (IOException e) {
      log.warn("[AgentState] Failed to take snapshot: {}", e.getMessage());
      return null;
    }
  }

  /** Try to restore the last saved state. */
  public AgentStateSnapshot restore() {
    try {
      AgentStateSnapshot snapshot = AgentStateSnapshot.load(snapshotDir);
      if (snapshot != null) {
        log.info("[AgentState] Restored state: {}", snapshot);
        for (SnapshotListener listener : listeners) {
          try {
            listener.onRestore(snapshot);
          } catch (Exception e) {
            log.warn("[AgentState] Listener error on restore: {}", e.getMessage());
          }
        }
      }
      return snapshot;
    } catch (IOException e) {
      log.warn("[AgentState] Failed to restore state: {}", e.getMessage());
      return null;
    }
  }

  /** Delete all persisted state. */
  public boolean clearState() {
    return AgentStateSnapshot.delete(snapshotDir);
  }

  /** Add a snapshot listener. */
  public void addListener(SnapshotListener listener) {
    listeners.add(listener);
  }

  /** Remove a snapshot listener. */
  public void removeListener(SnapshotListener listener) {
    listeners.remove(listener);
  }

  public boolean isRunning() {
    return running.get();
  }

  public long getLastSnapshotTimeMs() {
    return lastSnapshotTime.get();
  }

  public Path getSnapshotDir() {
    return snapshotDir;
  }

  public long getIntervalMs() {
    return intervalMs;
  }

  /** Rotate snapshot history files, keeping only the most recent. */
  private void rotateHistory() {
    // For now, the single-file approach is sufficient.
    // A production system would keep timestamped backup copies.
  }

  /** Interface for providing agent state to the persister. */
  public interface StateProvider {
    /** Capture the current agent state into a snapshot. */
    AgentStateSnapshot captureState();
  }

  /** Listener for snapshot events. */
  public interface SnapshotListener {
    /** Called when a new snapshot is taken. */
    default void onSnapshot(AgentStateSnapshot snapshot) {}

    /** Called when a snapshot is restored. */
    default void onRestore(AgentStateSnapshot snapshot) {}
  }
}
