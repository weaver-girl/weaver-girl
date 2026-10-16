package com.github.cc11001100.weavergirl.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentStateSnapshotTest {

  @TempDir Path tempDir;

  private Path stateDir;

  @BeforeEach
  void setUp() {
    stateDir = tempDir.resolve("state-test");
  }

  @AfterEach
  void tearDown() {
    // Cleanup handled by @TempDir
  }

  @Test
  void emptySnapshot_hasVersionAndTime() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    assertTrue(snapshot.getSnapshotTimeMs() > 0);
    assertEquals(0, snapshot.getInterceptorCount());
    assertEquals(0, snapshot.getTransformedClassCount());
    assertEquals(0, snapshot.getTotalInvocationCount());
    assertEquals(0, snapshot.getTotalErrorCount());
  }

  @Test
  void setInterceptorCount_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setInterceptorCount(42);
    assertEquals(42, snapshot.getInterceptorCount());
  }

  @Test
  void setTransformedClassCount_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setTransformedClassCount(100);
    assertEquals(100, snapshot.getTransformedClassCount());
  }

  @Test
  void setTotalInvocationCount_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setTotalInvocationCount(999999L);
    assertEquals(999999L, snapshot.getTotalInvocationCount());
  }

  @Test
  void setTotalErrorCount_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setTotalErrorCount(50);
    assertEquals(50, snapshot.getTotalErrorCount());
  }

  @Test
  void setAgentUptimeMs_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setAgentUptimeMs(3600000);
    assertEquals(3600000, snapshot.getAgentUptimeMs());
  }

  @Test
  void setSamplingRate_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setSamplingRate(10);
    assertEquals(10, snapshot.getSamplingRate());
  }

  @Test
  void setCircuitBreakerThreshold_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setCircuitBreakerThreshold(5);
    assertEquals(5, snapshot.getCircuitBreakerThreshold());
  }

  @Test
  void setConfig_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setConfig("samplingRate", "10");
    snapshot.setConfig("circuitBreaker", "true");
    assertEquals("10", snapshot.getConfig("samplingRate"));
    assertEquals("true", snapshot.getConfig("circuitBreaker"));
    assertNull(snapshot.getConfig("nonexistent"));
  }

  @Test
  void getAllConfig_returnsAllConfigs() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setConfig("key1", "val1");
    snapshot.setConfig("key2", "val2");
    Map<String, String> configs = snapshot.getAllConfig();
    assertEquals(2, configs.size());
    assertEquals("val1", configs.get("key1"));
    assertEquals("val2", configs.get("key2"));
  }

  @Test
  void setPluginState_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setPluginState("jdbc", "ACTIVE");
    snapshot.setPluginState("servlet", "DISABLED");
    assertEquals("ACTIVE", snapshot.getPluginState("jdbc"));
    assertEquals("DISABLED", snapshot.getPluginState("servlet"));
    assertEquals("UNKNOWN", snapshot.getPluginState("nonexistent"));
  }

  @Test
  void getAllPluginStates_returnsAllPlugins() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setPluginState("jdbc", "ACTIVE");
    snapshot.setPluginState("redis", "LOADED");
    Map<String, String> plugins = snapshot.getAllPluginStates();
    assertEquals(2, plugins.size());
    assertEquals("ACTIVE", plugins.get("jdbc"));
    assertEquals("LOADED", plugins.get("redis"));
  }

  @Test
  void setMetric_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setMetric("slow_queries", 42);
    snapshot.setMetric("errors", 5);
    assertEquals(42, snapshot.getMetric("slow_queries"));
    assertEquals(5, snapshot.getMetric("errors"));
    assertEquals(0, snapshot.getMetric("nonexistent"));
  }

  @Test
  void getAllMetrics_returnsAllMetrics() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setMetric("m1", 10);
    snapshot.setMetric("m2", 20);
    Map<String, String> metrics = snapshot.getAllMetrics();
    assertEquals(2, metrics.size());
  }

  @Test
  void setHealth_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setHealth("memory", "HEALTHY");
    snapshot.setHealth("interceptors", "DEGRADED");
    assertEquals("HEALTHY", snapshot.getHealth("memory"));
    assertEquals("DEGRADED", snapshot.getHealth("interceptors"));
    assertEquals("UNKNOWN", snapshot.getHealth("nonexistent"));
  }

  @Test
  void getAllHealth_returnsAllHealth() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setHealth("h1", "UP");
    snapshot.setHealth("h2", "DOWN");
    Map<String, String> health = snapshot.getAllHealth();
    assertEquals(2, health.size());
  }

  @Test
  void setCustom_storesAndRetrieves() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setCustom("customKey", "customVal");
    assertEquals("customVal", snapshot.getCustom("customKey"));
    assertNull(snapshot.getCustom("nonexistent"));
  }

  /** A null status or value is stored as a placeholder, and a null custom key is ignored. */
  @Test
  void nullStatusAndValueAreStoredAsPlaceholders() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setHealth("memory", null);
    snapshot.setCustom(null, "ignored");
    snapshot.setCustom("note", null);

    assertEquals("UNKNOWN", snapshot.getHealth("memory"));
    assertEquals("", snapshot.getCustom("note"));
    assertNull(snapshot.getCustom(null));
  }

  void setCustom_ignoresAgentPrefix() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setCustom("agent.should-be-ignored", "val");
    assertNull(snapshot.getCustom("agent.should-be-ignored"));
  }

  @Test
  void setConfig_nullValue_storesEmpty() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setConfig("key", null);
    assertEquals("", snapshot.getConfig("key"));
  }

  @Test
  void setPluginState_nullState_storesUnknown() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setPluginState("test", null);
    assertEquals("UNKNOWN", snapshot.getPluginState("test"));
  }

  @Test
  void chaining_allSetters() {
    AgentStateSnapshot snapshot =
        new AgentStateSnapshot()
            .setInterceptorCount(10)
            .setTransformedClassCount(50)
            .setTotalInvocationCount(1000)
            .setTotalErrorCount(5)
            .setAgentUptimeMs(60000)
            .setSamplingRate(5)
            .setCircuitBreakerThreshold(3)
            .setConfig("key", "val")
            .setPluginState("test", "ACTIVE")
            .setMetric("m", 100)
            .setHealth("h", "UP")
            .setCustom("c", "v");

    assertEquals(10, snapshot.getInterceptorCount());
    assertEquals(50, snapshot.getTransformedClassCount());
    assertEquals(1000, snapshot.getTotalInvocationCount());
    assertEquals(5, snapshot.getTotalErrorCount());
    assertEquals(60000, snapshot.getAgentUptimeMs());
    assertEquals(5, snapshot.getSamplingRate());
    assertEquals(3, snapshot.getCircuitBreakerThreshold());
    assertEquals("val", snapshot.getConfig("key"));
    assertEquals("ACTIVE", snapshot.getPluginState("test"));
    assertEquals(100, snapshot.getMetric("m"));
    assertEquals("UP", snapshot.getHealth("h"));
    assertEquals("v", snapshot.getCustom("c"));
  }

  @Test
  void save_andLoad_roundtrip() throws IOException {
    AgentStateSnapshot original =
        new AgentStateSnapshot()
            .setInterceptorCount(15)
            .setTransformedClassCount(30)
            .setTotalInvocationCount(5000)
            .setTotalErrorCount(10)
            .setAgentUptimeMs(120000)
            .setSamplingRate(8)
            .setCircuitBreakerThreshold(5)
            .setConfig("slowThreshold", "3000")
            .setPluginState("jdbc", "ACTIVE")
            .setPluginState("redis", "DISABLED")
            .setMetric("slow_queries", 42)
            .setHealth("memory", "HEALTHY")
            .setCustom("myKey", "myVal");

    Path saved = original.save(stateDir);
    assertTrue(Files.exists(saved));

    AgentStateSnapshot loaded = AgentStateSnapshot.load(stateDir);
    assertNotNull(loaded);

    assertEquals(15, loaded.getInterceptorCount());
    assertEquals(30, loaded.getTransformedClassCount());
    assertEquals(5000, loaded.getTotalInvocationCount());
    assertEquals(10, loaded.getTotalErrorCount());
    assertEquals(120000, loaded.getAgentUptimeMs());
    assertEquals(8, loaded.getSamplingRate());
    assertEquals(5, loaded.getCircuitBreakerThreshold());
    assertEquals("3000", loaded.getConfig("slowThreshold"));
    assertEquals("ACTIVE", loaded.getPluginState("jdbc"));
    assertEquals("DISABLED", loaded.getPluginState("redis"));
    assertEquals(42, loaded.getMetric("slow_queries"));
    assertEquals("HEALTHY", loaded.getHealth("memory"));
    assertEquals("myVal", loaded.getCustom("myKey"));
  }

  @Test
  void load_nonexistent_returnsNull() throws IOException {
    AgentStateSnapshot loaded = AgentStateSnapshot.load(stateDir);
    assertNull(loaded);
  }

  @Test
  void delete_removesFile() throws IOException {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.save(stateDir);
    assertTrue(AgentStateSnapshot.exists(stateDir));

    boolean deleted = AgentStateSnapshot.delete(stateDir);
    assertTrue(deleted);
    assertFalse(AgentStateSnapshot.exists(stateDir));
  }

  @Test
  void delete_nonexistent_returnsFalse() {
    assertFalse(AgentStateSnapshot.delete(stateDir));
  }

  @Test
  void exists_nonexistent_returnsFalse() {
    assertFalse(AgentStateSnapshot.exists(stateDir));
  }

  @Test
  void exists_afterSave_returnsTrue() throws IOException {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.save(stateDir);
    assertTrue(AgentStateSnapshot.exists(stateDir));
  }

  /**
   * The no-argument forms use the default directory. They save, find, reload and remove a snapshot, and
   * the directory is removed again afterwards so nothing is left in the working tree.
   */
  @Test
  void theDefaultLocationRoundTrips() throws IOException {
    java.nio.file.Path defaults = java.nio.file.Paths.get("weaver-girl-state");
    try {
      AgentStateSnapshot.delete();
      assertFalse(AgentStateSnapshot.exists());

      new AgentStateSnapshot().setInterceptorCount(4).save();

      assertTrue(AgentStateSnapshot.exists());
      assertEquals(4, AgentStateSnapshot.load().getInterceptorCount());
      assertTrue(AgentStateSnapshot.delete());
      assertNull(AgentStateSnapshot.load());
    } finally {
      AgentStateSnapshot.delete();
      java.nio.file.Files.deleteIfExists(defaults);
    }
  }

  /** A stored count that is not a number reads back as zero rather than failing the load. */
  @Test
  void aNonNumericCountReadsBackAsZero() throws IOException {
    java.nio.file.Files.createDirectories(stateDir);
    java.nio.file.Files.write(
        stateDir.resolve("agent-state.properties"),
        "interceptor.count=not-a-number\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertEquals(0, AgentStateSnapshot.load(stateDir).getInterceptorCount());
  }

  /** A snapshot that cannot be deleted is reported as not deleted rather than thrown. */
  @Test
  void aSnapshotThatCannotBeDeletedIsReported() throws IOException {
    java.nio.file.Path blocked = stateDir.resolve("blocked");
    java.nio.file.Files.createDirectories(blocked);
    // A non-empty directory where the snapshot file should be cannot be deleted as a file.
    java.nio.file.Path obstacle = blocked.resolve("agent-state.properties");
    java.nio.file.Files.createDirectory(obstacle);
    java.nio.file.Files.write(obstacle.resolve("held"), new byte[] {1});

    assertFalse(AgentStateSnapshot.delete(blocked));
  }

  @Test
  void getAllState_returnsUnmodifiable() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.setConfig("key", "val");
    Map<String, String> state = snapshot.getAllState();
    assertThrows(UnsupportedOperationException.class, () -> state.put("new", "value"));
  }

  @Test
  void size_returnsEntryCount() {
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    int baseSize = snapshot.size();
    snapshot.setInterceptorCount(1);
    assertEquals(baseSize + 1, snapshot.size());
  }

  @Test
  void toString_containsUsefulInfo() {
    AgentStateSnapshot snapshot =
        new AgentStateSnapshot().setInterceptorCount(10).setTotalInvocationCount(500);
    String str = snapshot.toString();
    assertTrue(str.contains("10"));
    assertTrue(str.contains("500"));
  }

  @Test
  void constructor_fromMap_restoresState() {
    Map<String, String> map = new java.util.HashMap<>();
    map.put(AgentStateSnapshot.KEY_INTERCEPTOR_COUNT, "20");
    map.put(AgentStateSnapshot.KEY_TOTAL_INVOCATION_COUNT, "1000");

    AgentStateSnapshot snapshot = new AgentStateSnapshot(map);
    assertEquals(20, snapshot.getInterceptorCount());
    assertEquals(1000, snapshot.getTotalInvocationCount());
  }

  @Test
  void save_createsDirectoryIfMissing() throws IOException {
    Path nested = tempDir.resolve("a").resolve("b").resolve("c");
    AgentStateSnapshot snapshot = new AgentStateSnapshot();
    snapshot.save(nested);
    assertTrue(Files.exists(nested.resolve("agent-state.properties")));
  }
}
