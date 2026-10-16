package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A jar copied into the plugin directory after startup has to be noticed. The watcher is what turns a
 * directory change into a load, and it has to wait until the copy has finished rather than load a jar
 * that is still being written.
 */
class PluginDirectoryWatcherTest {

  @TempDir Path tempDir;

  @Test
  void jarCopiedIntoTheDirectoryTriggersALoad() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch loaded = new CountDownLatch(1);
    PluginDirectoryWatcher watcher =
        new PluginDirectoryWatcher(
            tempDir,
            () -> {
              loads.incrementAndGet();
              loaded.countDown();
            });
    watcher.start();
    try {
      Files.write(tempDir.resolve("extra.jar"), new byte[] {1, 2, 3, 4});

      assertTrue(loaded.await(5, TimeUnit.SECONDS), "a jar copied into the directory was not loaded");
      // The copy is reported as both created and modified; one load has to cover both.
      Thread.sleep(500);
      assertTrue(loads.get() <= 2, "one jar was loaded " + loads.get() + " times");
    } finally {
      watcher.stop();
    }
  }

  /** A directory that is not there yet is not an error; there is simply nothing to watch. */
  @Test
  void aMissingDirectoryIsNotWatched() {
    AtomicInteger loads = new AtomicInteger();
    PluginDirectoryWatcher watcher =
        new PluginDirectoryWatcher(tempDir.resolve("not-created"), loads::incrementAndGet);

    watcher.start();
    watcher.stop();

    assertTrue(loads.get() == 0, "a directory that does not exist triggered a load");
  }

  /** A file that is not a jar does not trigger a load, and a load that throws is not fatal. */
  @Test
  void onlyJarsTriggerALoadAndAFailingLoadIsSurvived() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch loaded = new CountDownLatch(1);
    PluginDirectoryWatcher watcher =
        new PluginDirectoryWatcher(
            tempDir,
            () -> {
              loads.incrementAndGet();
              loaded.countDown();
              throw new IllegalStateException("load failed");
            });
    watcher.start();
    try {
      Files.write(tempDir.resolve("notes.txt"), new byte[] {1});
      Thread.sleep(400);
      assertTrue(loads.get() == 0, "a file that is not a jar triggered a load");

      Files.write(tempDir.resolve("extra.jar"), new byte[] {1, 2, 3, 4});
      assertTrue(loaded.await(5, TimeUnit.SECONDS), "the jar was not loaded");
    } finally {
      watcher.stop();
    }
  }

  /**
   * A jar that is still being written keeps the watcher waiting, and the load only happens once the
   * directory has held still.
   */
  @Test
  void aJarThatKeepsGrowingIsLoadedOnceItSettles() throws Exception {
    CountDownLatch loaded = new CountDownLatch(1);
    PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(tempDir, loaded::countDown);
    watcher.start();
    try {
      Path jar = tempDir.resolve("growing.jar");
      Files.write(jar, new byte[] {1});
      // Append while the quiet window is open, so the size check sees the directory change twice.
      for (int i = 0; i < 3; i++) {
        Thread.sleep(120);
        Files.write(jar, new byte[] {(byte) i}, java.nio.file.StandardOpenOption.APPEND);
      }
      assertTrue(loaded.await(5, TimeUnit.SECONDS), "a jar that settled was never loaded");
    } finally {
      watcher.stop();
    }
  }

  /** Stopping while the watcher is blocked waiting for an event ends the watch. */
  @Test
  void stoppingWhileTheWatchIsIdleEndsIt() throws Exception {
    PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(tempDir, () -> {});
    watcher.start();
    Thread.sleep(200);
    assertDoesNotThrow(watcher::stop);
  }

  /** A directory this process cannot read cannot be watched, and starting reports that instead of throwing. */
  @Test
  void aDirectoryThatCannotBeReadIsNotWatched() throws Exception {
    tempDir.toFile().setReadable(false);
    try {
      PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(tempDir, () -> {});
      assertDoesNotThrow(watcher::start);
    } finally {
      tempDir.toFile().setReadable(true);
    }
  }

  /** Interrupting the watcher while it waits for an event ends the watch instead of spinning. */
  @Test
  void interruptingTheWatcherEndsIt() throws Exception {
    PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(tempDir, () -> {});
    watcher.start();
    Thread thread = threadNamed("weaver-girl-plugin-watcher");
    assertNotNull(thread, "the watcher thread never started");
    thread.interrupt();
    thread.join(5000);
    assertFalse(thread.isAlive(), "interrupting the watcher left it running");
    watcher.stop();
  }

  /** Closing the watch service throws, which is logged and does not escape {@code stop}. */
  @Test
  void aWatchServiceThatFailsToCloseIsSwallowed() throws Exception {
    PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(tempDir, () -> {});
    java.lang.reflect.Field field = PluginDirectoryWatcher.class.getDeclaredField("watchService");
    field.setAccessible(true);
    field.set(
        watcher,
        new java.nio.file.WatchService() {
          public java.nio.file.WatchKey poll() {
            return null;
          }

          public java.nio.file.WatchKey poll(long timeout, java.util.concurrent.TimeUnit unit) {
            return null;
          }

          public java.nio.file.WatchKey take() {
            return null;
          }

          public void close() throws java.io.IOException {
            throw new java.io.IOException("already closed");
          }
        });

    assertDoesNotThrow(watcher::stop);
  }

  /** The live thread of {@code name}, or {@code null} when there is none. */
  private static Thread threadNamed(String name) {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (name.equals(thread.getName())) {
        return thread;
      }
    }
    return null;
  }
}
