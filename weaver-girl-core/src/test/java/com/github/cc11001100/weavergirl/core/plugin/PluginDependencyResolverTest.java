package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Dependency resolution decides which plugins load and in what order, so losing a plugin here means
 * it silently never loads.
 */
class PluginDependencyResolverTest {

  @Test
  void ordersPluginsSoDependenciesComeFirst() {
    WeaverPlugin storage = plugin("storage");
    WeaverPlugin audit = plugin("audit", "storage");

    List<WeaverPlugin> resolved =
        new PluginDependencyResolver().resolve(Arrays.asList(audit, storage));

    assertEquals(Arrays.asList("storage", "audit"), names(resolved));
  }

  @Test
  void keepsEveryPluginWhenTwoShareAName() {
    // Two plugins can legitimately be named alike: a built-in and a drop-in that replaces it, or
    // two jars that both forgot to pick a distinct name. The resolver indexes by name, and a map
    // keeps one entry per key, so the second used to erase the first and it never loaded.
    WeaverPlugin first = plugin("metrics");
    WeaverPlugin second = plugin("metrics");

    List<WeaverPlugin> resolved =
        new PluginDependencyResolver().resolve(Arrays.asList(first, second));

    assertEquals(2, resolved.size(), "a duplicate name dropped a plugin from the load");
    assertTrue(resolved.contains(first));
    assertTrue(resolved.contains(second));
  }

  @Test
  void loadsWhatItCanWhenADependencyIsMissing() {
    WeaverPlugin lone = plugin("audit", "storage");

    List<WeaverPlugin> resolved =
        new PluginDependencyResolver().resolve(Collections.singletonList(lone));

    assertEquals(Collections.singletonList(lone), resolved);
  }

  @Test
  void leavesOutAPluginThatRequiresSomethingNotLoaded() {
    WeaverPlugin audit = requiring("audit", "storage");
    WeaverPlugin report = requiring("report", "audit");

    PluginDependencyResolver resolver = new PluginDependencyResolver();
    List<WeaverPlugin> resolved = resolver.resolve(Arrays.asList(report, audit));

    assertTrue(resolved.isEmpty(), "a plugin loaded without the plugin it requires: " + names(resolved));
    assertEquals("storage", resolver.unsatisfied().get(audit));
    assertEquals("audit", resolver.unsatisfied().get(report));
  }

  @Test
  void stillLoadsAPluginThatOnlyDependsOnSomethingMissing() {
    WeaverPlugin audit = plugin("audit", "storage");
    WeaverPlugin report = requiring("report", "audit");

    PluginDependencyResolver resolver = new PluginDependencyResolver();
    List<WeaverPlugin> resolved = resolver.resolve(Arrays.asList(report, audit));

    assertEquals(Arrays.asList("audit", "report"), names(resolved));
    assertTrue(resolver.unsatisfied().isEmpty());
  }

  private static List<String> names(List<WeaverPlugin> plugins) {
    List<String> names = new java.util.ArrayList<>();
    for (WeaverPlugin plugin : plugins) {
      names.add(plugin.name());
    }
    return names;
  }

  /** A plugin that refuses to load unless every named plugin loaded too. */
  private static WeaverPlugin requiring(String name, String... requirements) {
    return new WeaverPlugin() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String[] requires() {
        return requirements;
      }

      @Override
      public void init(PluginContext context) {}

      @Override
      public void registerInterceptors(InterceptorRegistry registry) {}

      @Override
      public void destroy() {}
    };
  }

  private static WeaverPlugin plugin(String name, String... dependencies) {
    return new WeaverPlugin() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String[] depends() {
        return dependencies;
      }

      @Override
      public void init(PluginContext context) {}

      @Override
      public void registerInterceptors(InterceptorRegistry registry) {}

      @Override
      public void destroy() {}
    };
  }
}
