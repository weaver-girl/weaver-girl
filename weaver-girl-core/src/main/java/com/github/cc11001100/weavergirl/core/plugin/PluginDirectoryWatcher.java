package com.github.cc11001100.weavergirl.core.plugin;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a plugin directory and loads jars that appear, or unloads plugins whose jar disappears,
 * after the agent has started.
 *
 * <p>The directory is scanned once at startup, which is the only moment a plugin used to be able to
 * load. A product that ships another plugin later — or enables one by copying its jar in — had to
 * restart the process. This watches the directory and loads each new jar as it lands.
 *
 * <p>A jar is loaded once it has stopped growing. It is usually copied into place rather than written
 * directly, and loading it mid-copy reads a truncated zip.
 */
public final class PluginDirectoryWatcher {

  private static final Logger log = LoggerFactory.getLogger(PluginDirectoryWatcher.class);

  /** How long a jar must stay the same size before it is loaded. */
  private static final long QUIET_MILLIS = 300L;

  private final Path directory;
  private final Runnable onChange;
  private volatile boolean running = true;
  private Thread thread;
  private WatchService watchService;

  /**
   * @param directory the plugin directory to watch
   * @param onChange run when the directory's contents may have changed; it loads whatever is new
   */
  public PluginDirectoryWatcher(Path directory, Runnable onChange) {
    this.directory = directory;
    this.onChange = onChange;
  }

  /** Start watching. Does nothing when the directory does not exist. */
  public void start() {
    if (!directory.toFile().isDirectory()) {
      log.info("Plugin directory does not exist, not watching: {}", directory);
      return;
    }
    try {
      watchService = FileSystems.getDefault().newWatchService();
      directory.register(
          watchService,
          StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_DELETE);
    } catch (IOException e) {
      log.warn("Cannot watch plugin directory {}: {}", directory, e.getMessage());
      return;
    }
    thread =
        new Thread(this::watch, "weaver-girl-plugin-watcher");
    thread.setDaemon(true);
    thread.start();
    log.info("Watching plugin directory for new jars: {}", directory);
  }

  /** Stop watching. */
  public void stop() {
    running = false;
    if (watchService != null) {
      try {
        watchService.close();
      } catch (IOException e) {
        log.debug("Closing plugin watch failed: {}", e.getMessage());
      }
    }
    if (thread != null) {
      thread.interrupt();
    }
  }

  private void watch() {
    while (running) {
      WatchKey key;
      try {
        key = watchService.take();
      } catch (ClosedWatchServiceException e) {
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      boolean jarChanged = false;
      for (WatchEvent<?> event : key.pollEvents()) {
        if (event.context() instanceof Path
            && ((Path) event.context()).toString().endsWith(".jar")) {
          jarChanged = true;
        }
      }
      key.reset();
      if (jarChanged) {
        loadWhenQuiet();
      }
    }
  }

  /** Load once no jar in the directory has changed size for {@link #QUIET_MILLIS}. */
  private void loadWhenQuiet() {
    long lastSize = -1L;
    long stableSince = System.currentTimeMillis();
    while (running && System.currentTimeMillis() - stableSince < QUIET_MILLIS) {
      long size = directorySize();
      if (size != lastSize) {
        lastSize = size;
        stableSince = System.currentTimeMillis();
      }
      try {
        Thread.sleep(100L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (running) {
      try {
        onChange.run();
      } catch (RuntimeException e) {
        log.warn("Loading new plugins failed: {}", e.getMessage());
      }
    }
  }

  /** The summed size of every jar in the directory, which changes while one is still being copied. */
  private long directorySize() {
    long total = 0L;
    java.io.File[] jars = directory.toFile().listFiles(file -> file.getName().endsWith(".jar"));
    if (jars != null) {
      for (java.io.File jar : jars) {
        total += jar.length();
      }
    }
    return total;
  }
}
