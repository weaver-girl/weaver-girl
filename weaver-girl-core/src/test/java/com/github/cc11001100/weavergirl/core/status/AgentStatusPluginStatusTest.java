package com.github.cc11001100.weavergirl.core.status;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentStatus.PluginStatus.
 */
class AgentStatusPluginStatusTest {

  private AgentStatus status;

  @BeforeEach
  void setUp() {
    status = AgentStatus.getInstance();
    status.reset();
  }

  @Test
  void recordPluginStatus_loaded() {
    status.recordPluginStatus("test-plugin", true, null);

    Map<String, AgentStatus.PluginStatus> statuses = status.getPluginStatuses();
    assertTrue(statuses.containsKey("test-plugin"));
    assertTrue(statuses.get("test-plugin").isLoaded());
    assertNull(statuses.get("test-plugin").getError());
  }

  @Test
  void recordPluginStatus_failed() {
    status.recordPluginStatus("failed-plugin", false, "ClassNotFoundException");

    Map<String, AgentStatus.PluginStatus> statuses = status.getPluginStatuses();
    assertTrue(statuses.containsKey("failed-plugin"));
    assertFalse(statuses.get("failed-plugin").isLoaded());
    assertEquals("ClassNotFoundException", statuses.get("failed-plugin").getError());
  }

  @Test
  void recordMultiplePluginStatuses() {
    status.recordPluginStatus("plugin-a", true, null);
    status.recordPluginStatus("plugin-b", false, "error");
    status.recordPluginStatus("plugin-c", true, null);

    Map<String, AgentStatus.PluginStatus> statuses = status.getPluginStatuses();
    assertEquals(3, statuses.size());
    assertTrue(statuses.get("plugin-a").isLoaded());
    assertFalse(statuses.get("plugin-b").isLoaded());
    assertTrue(statuses.get("plugin-c").isLoaded());
  }

  @Test
  void overwritePluginStatus() {
    status.recordPluginStatus("plugin", true, null);
    status.recordPluginStatus("plugin", false, "retry failed");

    Map<String, AgentStatus.PluginStatus> statuses = status.getPluginStatuses();
    assertEquals(1, statuses.size());
    assertFalse(statuses.get("plugin").isLoaded());
    assertEquals("retry failed", statuses.get("plugin").getError());
  }
}
