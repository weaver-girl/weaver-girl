package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PluginDependencyResolverTest {

  private final PluginDependencyResolver resolver = new PluginDependencyResolver();

  // --- Helper to create mock plugins ---

  private static WeaverPlugin plugin(String name, String... depends) {
    return new WeaverPlugin() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void registerInterceptors(InterceptorRegistry registry) {}

      @Override
      public String[] depends() {
        return depends;
      }
    };
  }

  private List<String> names(List<WeaverPlugin> plugins) {
    return plugins.stream().map(WeaverPlugin::name).collect(Collectors.toList());
  }

  // --- Tests ---

  @Test
  void noDependencies_preservesOriginalOrder() {
    WeaverPlugin a = plugin("a");
    WeaverPlugin b = plugin("b");
    WeaverPlugin c = plugin("c");

    List<WeaverPlugin> result = resolver.resolve(Arrays.asList(a, b, c));

    assertEquals(Arrays.asList("a", "b", "c"), names(result));
  }

  @Test
  void aDependsOnB_bLoadsBeforeA() {
    WeaverPlugin a = plugin("a", "b");
    WeaverPlugin b = plugin("b");

    List<WeaverPlugin> result = resolver.resolve(Arrays.asList(a, b));

    assertEquals(Arrays.asList("b", "a"), names(result));
  }

  @Test
  void diamondDependency_bothDependOnB() {
    WeaverPlugin a = plugin("a", "b");
    WeaverPlugin c = plugin("c", "b");
    WeaverPlugin b = plugin("b");

    List<WeaverPlugin> result = resolver.resolve(Arrays.asList(a, c, b));

    List<String> resolved = names(result);
    // b must come before both a and c
    assertTrue(resolved.indexOf("b") < resolved.indexOf("a"));
    assertTrue(resolved.indexOf("b") < resolved.indexOf("c"));
    assertEquals(3, resolved.size());
  }

  @Test
  void circularDependency_loggedAsWarning_doesNotThrow() {
    WeaverPlugin a = plugin("a", "b");
    WeaverPlugin b = plugin("b", "a");

    // Should not throw
    List<WeaverPlugin> result = resolver.resolve(Arrays.asList(a, b));

    // Both plugins should still be present
    assertEquals(2, result.size());
    assertTrue(names(result).contains("a"));
    assertTrue(names(result).contains("b"));
  }

  @Test
  void missingDependency_loggedAsWarning_continues() {
    WeaverPlugin a = plugin("a", "nonexistent");

    List<WeaverPlugin> result = resolver.resolve(Arrays.asList(a));

    assertEquals(Arrays.asList("a"), names(result));
  }

  @Test
  void singlePlugin_returnedAsIs() {
    WeaverPlugin a = plugin("a");

    List<WeaverPlugin> result = resolver.resolve(Arrays.asList(a));

    assertEquals(Arrays.asList("a"), names(result));
  }

  @Test
  void transitiveDependency_aDependsOnB_bDependsOnC() {
    WeaverPlugin a = plugin("a", "b");
    WeaverPlugin b = plugin("b", "c");
    WeaverPlugin c = plugin("c");

    List<WeaverPlugin> result = resolver.resolve(Arrays.asList(a, b, c));

    assertEquals(Arrays.asList("c", "b", "a"), names(result));
  }

  @Test
  void emptyList_returnsEmptyList() {
    List<WeaverPlugin> result = resolver.resolve(new ArrayList<>());

    assertTrue(result.isEmpty());
  }
}
