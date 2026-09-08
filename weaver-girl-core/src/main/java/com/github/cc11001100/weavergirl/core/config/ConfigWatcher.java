package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.File;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a YAML configuration file for changes and triggers hot-reload. Uses a dedicated daemon
 * thread with WatchService to monitor file modifications.
 *
 * <p>When the config file changes:
 *
 * <ol>
 *   <li>All previously registered YAML interceptors are unregistered
 *   <li>The new config is loaded and new interceptors are registered
 * </ol>
 */
public class ConfigWatcher {

  private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

  private final String configPath;
  private final InterceptorRegistry registry;
  private final YamlConfigLoader configLoader;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile long lastReloadTime = 0;
  private static final long DEBOUNCE_MILLIS = 2000;
  private Thread watcherThread;
  private Runnable afterReloadCallback;

  public ConfigWatcher(String configPath, InterceptorRegistry registry) {
    this.configPath = configPath;
    this.registry = registry;
    this.configLoader = new YamlConfigLoader();
  }

  /**
   * Set a callback to be invoked after a successful config reload. Typically used to trigger
   * retransformation of already-loaded classes.
   *
   * @param callback the callback to run after reload
   */
  public void setAfterReloadCallback(Runnable callback) {
    this.afterReloadCallback = callback;
  }

  /** Start watching the config file for changes. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return; // already running
    }

    File file = new File(configPath);
    if (!file.exists()) {
      log.warn("Config file does not exist, cannot watch: {}", configPath);
      running.set(false);
      return;
    }

    watcherThread =
        new Thread(
            () -> {
              try {
                Path dir = file.getAbsoluteFile().toPath().getParent();
                WatchService watchService = FileSystems.getDefault().newWatchService();
                dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

                log.info("Watching config file for changes: {}", configPath);

                while (running.get()) {
                  WatchKey key = watchService.take();
                  for (WatchEvent<?> event : key.pollEvents()) {
                    Path changedFile = (Path) event.context();
                    if (changedFile.toString().equals(file.getName())) {
                      long now = System.currentTimeMillis();
                      if (now - lastReloadTime < DEBOUNCE_MILLIS) {
                        continue; // skip duplicate event
                      }
                      lastReloadTime = now;
                      log.info("Config file changed, reloading: {}", configPath);
                      try {
                        // Unregister all YAML interceptors first
                        registry.getAllDefinitions().stream()
                            .filter(d -> d.getName().startsWith("yaml-"))
                            .forEach(d -> registry.unregister(d.getName()));
                        // Reload
                        configLoader.loadFromFile(configPath, registry);
                        log.info(
                            "Config reloaded with {} interceptors",
                            registry.getAllDefinitions().size());
                        // Trigger retransform after reload
                        if (afterReloadCallback != null) {
                          afterReloadCallback.run();
                        }
                      } catch (Exception e) {
                        log.error("Failed to reload config: {}", e.getMessage());
                      }
                    }
                  }
                  key.reset();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                log.error("Config watcher failed: {}", e.getMessage());
              }
            },
            "weaver-girl-config-watcher");

    watcherThread.setDaemon(true);
    watcherThread.start();
  }

  /** Stop watching the config file. */
  public void stop() {
    if (running.compareAndSet(true, false)) {
      if (watcherThread != null) {
        watcherThread.interrupt();
      }
      log.info("Config watcher stopped");
    }
  }
}
