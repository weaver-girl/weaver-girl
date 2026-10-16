package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A plugin that names the library it instruments registers nothing when that library is not on the
 * classpath, and registers normally when it is. The check has to happen in the loader: a plugin cannot
 * see the application classpath from its own loader, and loading the class to find out would define it
 * before it can be woven.
 */
class InstrumentedLibraryGateTest {

  private final AtomicInteger registrations = new AtomicInteger();

  /** Names a class that does not exist, so there is nothing for it to instrument. */
  class AbsentLibraryPlugin extends CountingPlugin {
    @Override
    public String name() {
      return "absent-library";
    }

    @Override
    public String instrumentedLibrary() {
      return "com.example.library.that.DoesNotExist";
    }
  }

  /** Names a class every runtime has, so the gate must let it through. */
  class PresentLibraryPlugin extends CountingPlugin {
    @Override
    public String name() {
      return "present-library";
    }

    @Override
    public String instrumentedLibrary() {
      return "java.lang.String";
    }
  }

  /**
   * Names two libraries and only one of them exists, so the plugin still has something to instrument and
   * must be activated. Which of the two it registers for is its own decision.
   */
  class EitherLibraryPlugin extends CountingPlugin {
    @Override
    public String name() {
      return "either-library";
    }

    @Override
    public String[] instrumentedLibraries() {
      return new String[] {"com.example.library.that.DoesNotExist", "java.lang.String"};
    }
  }

  /** Names nothing, so it is activated exactly as a plugin written before the gate existed. */
  class UngatedPlugin extends CountingPlugin {
    @Override
    public String name() {
      return "ungated";
    }
  }

  abstract class CountingPlugin extends AbstractPlugin {
    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
      registrations.incrementAndGet();
    }
  }

  /** A loader that discovers exactly the plugins this test hands it, instead of the classpath. */
  class FixedPluginLoader extends PluginLoader {
    private final WeaverPlugin[] plugins;

    FixedPluginLoader(WeaverPlugin... plugins) {
      this.plugins = plugins;
    }

    @Override
    java.util.List<WeaverPlugin> discover(ClassLoader classLoader) {
      return java.util.Arrays.asList(plugins);
    }
  }

  @Test
  void missingLibraryLeavesThePluginInactive() {
    AbsentLibraryPlugin plugin = new AbsentLibraryPlugin();
    PluginLoader loader = new FixedPluginLoader(plugin);

    loader.loadPlugins(
        getClass().getClassLoader(),
        new DefaultInterceptorRegistry(),
        Collections.<String, String>emptyMap());

    assertTrue(loader.getLoadedPlugins().isEmpty(), "a plugin for a missing library was activated");
    assertEquals(0, registrations.get(), "interceptors were registered against a missing library");
    assertFalse(probe().classPresent(null, "com.example.library.that.DoesNotExist"));
  }

  /** A context with no configuration, enough to ask about class presence. */
  private static PluginContext probe() {
    return new DefaultPluginContext(
        new DefaultInterceptorRegistry(), Collections.<String, String>emptyMap(), "probe");
  }

  @Test
  void presentLibraryAndUngatedPluginBothRegister() {
    PluginLoader loader = new FixedPluginLoader(new PresentLibraryPlugin(), new UngatedPlugin());

    java.util.List<WeaverPlugin> loaded =
        loader.loadPlugins(
            getClass().getClassLoader(),
            new DefaultInterceptorRegistry(),
            Collections.<String, String>emptyMap());

    assertEquals(2, loaded.size());
    assertEquals(2, registrations.get());
  }

  @Test
  void anyOneOfSeveralLibrariesActivatesThePlugin() {
    PluginLoader loader = new FixedPluginLoader(new EitherLibraryPlugin());

    java.util.List<WeaverPlugin> loaded =
        loader.loadPlugins(
            getClass().getClassLoader(),
            new DefaultInterceptorRegistry(),
            Collections.<String, String>emptyMap());

    assertEquals(1, loaded.size(), "a plugin was left inactive although one of its libraries exists");
    assertEquals(1, registrations.get());
  }

  @Test
  void classPresenceSeesTheApplicationLoaderBehindAChildFirstLoader() {
    // A plugin is loaded by a child-first loader whose parent is the application loader. The library
    // it instruments is on the parent, never on the child, so the check has to walk up.
    ClassLoader child = new URLClassLoader(new URL[0], getClass().getClassLoader());

    assertTrue(probe().classPresent(child, "java.lang.String"));
    assertFalse(probe().classPresent(child, "com.example.library.that.DoesNotExist"));
  }

  @Test
  void classPresenceDoesNotLoadTheClass() {
    PluginContext context =
        new DefaultPluginContext(
            new DefaultInterceptorRegistry(), Collections.<String, String>emptyMap(), "probe");

    assertTrue(context.classPresent(null, "java.lang.String"));
    assertFalse(context.classPresent(null, ""));
    assertFalse(context.classPresent(null, null));
  }
}
