package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.config.ConfigChangeEvent;
import com.github.cc11001100.weavergirl.api.config.ConfigChangeListener;
import com.github.cc11001100.weavergirl.api.config.ConfigSnapshot;
import com.github.cc11001100.weavergirl.api.config.DynamicConfigManager;
import com.github.cc11001100.weavergirl.api.config.DynamicConfigManagerHolder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default thread-safe implementation of {@link DynamicConfigManager}.
 *
 * <p>Manages configuration with:
 *
 * <ul>
 *   <li>Concurrent config storage
 *   <li>Key-specific and global change listeners
 *   <li>Versioned snapshots with rollback
 *   <li>Append-only audit log
 * </ul>
 *
 * @since 1.1.0
 */
public class DefaultDynamicConfigManager implements DynamicConfigManager {

  private static final Logger log = LoggerFactory.getLogger(DefaultDynamicConfigManager.class);

  private static final int MAX_SNAPSHOTS = 50;
  private static final int MAX_AUDIT_ENTRIES = 1000;

  private volatile ConcurrentHashMap<String, String> config = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<ConfigChangeListener> globalListeners =
      new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<ConfigChangeListener>> keyListeners =
      new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<ConfigSnapshot> snapshots = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<ConfigChangeEvent> auditLog = new CopyOnWriteArrayList<>();
  private final AtomicLong snapshotVersion = new AtomicLong(0);
  private volatile boolean shutdown = false;

  public DefaultDynamicConfigManager() {
    DynamicConfigManagerHolder.setInstance(this);
  }

  // ===== Read operations =====

  @Override
  public String get(String key) {
    return config.get(key);
  }

  @Override
  public String get(String key, String defaultValue) {
    return config.getOrDefault(key, defaultValue);
  }

  @Override
  public Map<String, String> getAll() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(config));
  }

  // ===== Write operations =====

  @Override
  public void set(String key, String value, String source) {
    checkShutdown();
    if (key == null || key.isEmpty()) {
      throw new IllegalArgumentException("Config key must not be null or empty");
    }
    String oldValue = config.put(key, value);
    if (!Objects.equals(oldValue, value)) {
      ConfigChangeEvent event = new ConfigChangeEvent(key, oldValue, value, source);
      auditLog.add(event);
      trimAuditLog();
      notifyListeners(event);
      log.debug(
          "[DynamicConfig] {} changed: '{}' -> '{}' (source={})", key, oldValue, value, source);
    }
  }

  @Override
  public void remove(String key, String source) {
    checkShutdown();
    String oldValue = config.remove(key);
    if (oldValue != null) {
      ConfigChangeEvent event = new ConfigChangeEvent(key, oldValue, null, source);
      auditLog.add(event);
      trimAuditLog();
      notifyListeners(event);
      log.debug("[DynamicConfig] {} removed (was '{}', source={})", key, oldValue, source);
    }
  }

  @Override
  public void setAll(Map<String, String> updates, String source) {
    checkShutdown();
    if (updates == null) {
      throw new IllegalArgumentException("Updates map must not be null");
    }
    for (Map.Entry<String, String> entry : updates.entrySet()) {
      set(entry.getKey(), entry.getValue(), source);
    }
  }

  // ===== Listeners =====

  @Override
  public void addListener(ConfigChangeListener listener) {
    if (listener != null) {
      globalListeners.add(listener);
    }
  }

  @Override
  public void addListener(String key, ConfigChangeListener listener) {
    if (key != null && listener != null) {
      keyListeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }
  }

  @Override
  public void removeListener(ConfigChangeListener listener) {
    globalListeners.remove(listener);
    for (CopyOnWriteArrayList<ConfigChangeListener> listeners : keyListeners.values()) {
      listeners.remove(listener);
    }
  }

  @Override
  public List<ConfigChangeListener> getListeners() {
    List<ConfigChangeListener> all = new ArrayList<>(globalListeners);
    for (CopyOnWriteArrayList<ConfigChangeListener> listeners : keyListeners.values()) {
      all.addAll(listeners);
    }
    return Collections.unmodifiableList(all);
  }

  // ===== Snapshots & Rollback =====

  @Override
  public ConfigSnapshot snapshot(String description) {
    checkShutdown();
    long version = snapshotVersion.incrementAndGet();
    ConfigSnapshot snap =
        new ConfigSnapshot(
            version, System.currentTimeMillis(), new LinkedHashMap<>(config), description);
    snapshots.add(snap);
    trimSnapshots();
    log.info(
        "[DynamicConfig] Snapshot v{} created: {} ({} entries)",
        version,
        description,
        config.size());
    return snap;
  }

  @Override
  public List<ConfigSnapshot> getSnapshots() {
    return Collections.unmodifiableList(new ArrayList<>(snapshots));
  }

  @Override
  public boolean rollback(long version) {
    checkShutdown();
    for (int i = snapshots.size() - 1; i >= 0; i--) {
      ConfigSnapshot snap = snapshots.get(i);
      if (snap.getVersion() == version) {
        doRollback(snap);
        return true;
      }
    }
    log.warn("[DynamicConfig] Rollback failed: snapshot v{} not found", version);
    return false;
  }

  @Override
  public boolean rollbackLast() {
    checkShutdown();
    if (snapshots.size() < 2) {
      log.warn("[DynamicConfig] Rollback failed: no previous snapshot available");
      return false;
    }
    // Rollback to second-to-last snapshot (last is the current state)
    ConfigSnapshot target = snapshots.get(snapshots.size() - 2);
    doRollback(target);
    return true;
  }

  private void doRollback(ConfigSnapshot target) {
    // Take a snapshot before rollback for safety
    snapshot("pre-rollback-to-v" + target.getVersion());

    // Clear current config and apply snapshot
    Map<String, String> oldConfig = new LinkedHashMap<>(config);
    // Atomic swap: build new config from target, then replace the reference entirely
    ConcurrentHashMap<String, String> newConfig = new ConcurrentHashMap<>(target.getConfig());
    this.config = newConfig;

    // Fire change events for each changed key
    String source = "rollback-to-v" + target.getVersion();
    Set<String> allKeys = new HashSet<>(oldConfig.keySet());
    allKeys.addAll(target.getConfig().keySet());

    for (String key : allKeys) {
      String oldValue = oldConfig.get(key);
      String newValue = target.getConfig().get(key);
      if (!Objects.equals(oldValue, newValue)) {
        ConfigChangeEvent event = new ConfigChangeEvent(key, oldValue, newValue, source);
        auditLog.add(event);
        notifyListeners(event);
      }
    }

    log.info(
        "[DynamicConfig] Rolled back to snapshot v{} ({})",
        target.getVersion(),
        target.getDescription());
  }

  // ===== Audit =====

  @Override
  public List<ConfigChangeEvent> getAuditLog() {
    return Collections.unmodifiableList(new ArrayList<>(auditLog));
  }

  @Override
  public List<ConfigChangeEvent> getAuditLog(String key) {
    List<ConfigChangeEvent> result = new ArrayList<>();
    for (ConfigChangeEvent event : auditLog) {
      if (key.equals(event.getKey())) {
        result.add(event);
      }
    }
    return Collections.unmodifiableList(result);
  }

  // ===== Lifecycle =====

  @Override
  public void loadFromSource(Map<String, String> incoming, String source) {
    checkShutdown();
    if (incoming == null) {
      return;
    }
    for (Map.Entry<String, String> entry : incoming.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      String oldValue = config.put(key, value);
      if (!Objects.equals(oldValue, value)) {
        ConfigChangeEvent event = new ConfigChangeEvent(key, oldValue, value, source);
        auditLog.add(event);
        notifyListeners(event);
      }
    }
    log.info("[DynamicConfig] Loaded {} entries from source '{}'", incoming.size(), source);
  }

  @Override
  public void shutdown() {
    shutdown = true;
    globalListeners.clear();
    keyListeners.clear();
    log.info("[DynamicConfig] Manager shut down");
  }

  // ===== Internal helpers =====

  private void notifyListeners(ConfigChangeEvent event) {
    // Notify key-specific listeners first
    CopyOnWriteArrayList<ConfigChangeListener> keySpecific = keyListeners.get(event.getKey());
    if (keySpecific != null) {
      for (ConfigChangeListener listener : keySpecific) {
        safeNotify(listener, event);
      }
    }
    // Then notify global listeners
    for (ConfigChangeListener listener : globalListeners) {
      safeNotify(listener, event);
    }
  }

  private void safeNotify(ConfigChangeListener listener, ConfigChangeEvent event) {
    try {
      listener.onConfigChange(event);
    } catch (Exception e) {
      log.warn(
          "[DynamicConfig] Listener {} failed on event {}: {}", listener, event, e.getMessage());
    }
  }

  private void trimSnapshots() {
    while (snapshots.size() > MAX_SNAPSHOTS) {
      snapshots.remove(0);
    }
  }

  private void trimAuditLog() {
    while (auditLog.size() > MAX_AUDIT_ENTRIES) {
      auditLog.remove(0);
    }
  }

  private void checkShutdown() {
    if (shutdown) {
      throw new IllegalStateException("DynamicConfigManager has been shut down");
    }
  }
}
