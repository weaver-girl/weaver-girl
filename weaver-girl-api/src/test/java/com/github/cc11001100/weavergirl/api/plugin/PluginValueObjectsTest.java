package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PluginValueObjectsTest {

  @Test
  void stateTransitionsFollowLifecycleRules() {
    assertTrue(PluginState.LOADED.canTransitionTo(PluginState.ACTIVE));
    assertTrue(PluginState.LOADED.canTransitionTo(PluginState.UNLOADED));
    assertTrue(PluginState.ACTIVE.canTransitionTo(PluginState.DISABLED));
    assertTrue(PluginState.ACTIVE.canTransitionTo(PluginState.UNLOADED));
    assertTrue(PluginState.DISABLED.canTransitionTo(PluginState.ACTIVE));
    assertTrue(PluginState.DISABLED.canTransitionTo(PluginState.UNLOADED));
    for (PluginState state : PluginState.values()) {
      assertFalse(state.canTransitionTo(state));
      assertFalse(PluginState.UNLOADED.canTransitionTo(state));
    }
  }

  @Test
  void pluginInfoExposesMetadata() {
    PluginInfo info = new PluginInfo("demo", PluginState.ACTIVE, null, 2, 10, 20);
    assertEquals("demo", info.getName());
    assertEquals(PluginState.ACTIVE, info.getState());
    assertNull(info.getVersion());
    assertEquals(2, info.getInterceptorCount());
    assertEquals(10, info.getLoadedAt());
    assertEquals(20, info.getLastStateChangedAt());
    assertTrue(info.toString().contains("demo"));
    assertTrue(info.toString().contains("interceptors=2"));
  }
}
