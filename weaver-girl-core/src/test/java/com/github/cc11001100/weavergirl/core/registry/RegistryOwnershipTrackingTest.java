package com.github.cc11001100.weavergirl.core.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Ownership recorded on the registering thread has to survive a nested registration and be forgotten
 * once the plugin is unloaded, including a class it declared rather than one its loader defined.
 */
class RegistryOwnershipTrackingTest {

  @AfterEach
  void tearDown() {
    DefaultInterceptorRegistry.clearTracking();
  }

  @Test
  void aNestedRegistrationRestoresTheOuterPlugin() {
    WeaverPlugin outer = plugin("outer");
    WeaverPlugin inner = plugin("inner");

    DefaultInterceptorRegistry.registeringAs(
        outer,
        () -> {
          assertSame(outer, DefaultInterceptorRegistry.registeringPlugin());
          DefaultInterceptorRegistry.registeringAs(
              inner, () -> assertSame(inner, DefaultInterceptorRegistry.registeringPlugin()));
          assertSame(outer, DefaultInterceptorRegistry.registeringPlugin(), "the outer plugin was lost");
        });

    assertNull(DefaultInterceptorRegistry.registeringPlugin());
  }

  @Test
  void untrackingForgetsAPluginAndTheClassItDeclared() {
    WeaverPlugin plugin = plugin("declared");
    DefaultInterceptorRegistry.track(plugin);
    DefaultInterceptorRegistry.track(plugin, String.class);

    // The plugin both has a loader and declared a class, so forgetting it walks both records.
    DefaultInterceptorRegistry.untrack(plugin);
    DefaultInterceptorRegistry.untrack(plugin);

    // Declaring the same class again must not trip over an entry the first untrack left behind.
    assertDoesNotThrow(() -> DefaultInterceptorRegistry.track(plugin, String.class));
  }

  @Test
  void untrackingAPluginWithoutALoaderDoesNotThrow() {
    assertDoesNotThrow(() -> DefaultInterceptorRegistry.untrack(null));
    assertDoesNotThrow(() -> DefaultInterceptorRegistry.track(null, (Class<?>) null));
  }

  private static WeaverPlugin plugin(String name) {
    return new WeaverPlugin() {
      public String name() {
        return name;
      }

      public void registerInterceptors(InterceptorRegistry registry) {}
    };
  }
}
