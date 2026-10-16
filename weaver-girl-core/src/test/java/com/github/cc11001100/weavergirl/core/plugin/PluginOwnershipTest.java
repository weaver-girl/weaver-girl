package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.junit.jupiter.api.Test;

/** Which plugin registered which interceptor. */
class PluginOwnershipTest {

  private static WeaverPlugin plugin(String called) {
    return new WeaverPlugin() {
      @Override
      public String name() {
        return called;
      }

      @Override
      public void registerInterceptors(InterceptorRegistry registry) {}
    };
  }

  /** A null plugin or a null definition records nothing. */
  @Test
  void aNullOwnerOrDefinitionIsIgnored() {
    PluginOwnership ownership = new PluginOwnership();
    ownership.record(null, "some-interceptor");
    ownership.record(plugin("jdbc"), null);

    assertTrue(ownership.pluginsOwning("some-interceptor").isEmpty());
  }

  /** The owner of a definition is found again, and an unknown definition has none. */
  @Test
  void aRecordedDefinitionNamesItsOwner() {
    PluginOwnership ownership = new PluginOwnership();
    ownership.record(plugin("jdbc"), "slow-query");

    assertEquals(java.util.Collections.singletonList("jdbc"), ownership.pluginsOwning("slow-query"));
    assertTrue(ownership.pluginsOwning("never-registered").isEmpty());
  }
}
