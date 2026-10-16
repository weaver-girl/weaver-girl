package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The SPI loading path only filters on {@code enabledPlugins} and {@code disabledPlugins}, and it
 * only loads sequentially when two plugins or fewer are discovered. The test classpath discovers far
 * more than that, so both need a class loader that offers exactly the plugins this test controls.
 */
class PluginLoaderSpiFilterTest {

  @Test
  void enabledPluginsIsAnAllowlistAndDisabledPluginsIsSkipped() {
    ClassLoader loader = plugins(new Allowed(), new Blocked(), new Other());

    PluginLoader allow = new PluginLoader();
    Map<String, String> enabled = new HashMap<>();
    enabled.put("enabledPlugins", "allowed");
    List<WeaverPlugin> loaded =
        allow.loadPlugins(loader, new DefaultInterceptorRegistry(), enabled);
    assertEquals(Collections.singletonList("allowed"), names(loaded));

    PluginLoader deny = new PluginLoader();
    Map<String, String> disabled = new HashMap<>();
    disabled.put("disabledPlugins", "blocked");
    loaded = deny.loadPlugins(loader, new DefaultInterceptorRegistry(), disabled);
    assertEquals(2, loaded.size());
    assertTrue(names(loaded).contains("allowed"));
    assertTrue(!names(loaded).contains("blocked"));
  }

  @Test
  void aPluginThatDisablesItselfOrThrowsIsNeitherLoadedNorFatal() {
    ClassLoader loader = plugins(new SwitchedOff(), new Exploding());

    PluginLoader pluginLoader = new PluginLoader();
    List<WeaverPlugin> loaded =
        pluginLoader.loadPlugins(loader, new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertTrue(loaded.isEmpty());
    // The one that threw is reported as failed, which is the summary the loader logs.
    assertEquals(0, pluginLoader.getLoadedPlugins().size());
  }

  @Test
  void contextClassLoaderOverloadLoadsWhateverThatLoaderOffers() {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(plugins(new Allowed()));
    try {
      PluginLoader pluginLoader = new PluginLoader();
      List<WeaverPlugin> loaded =
          pluginLoader.loadPlugins(new DefaultInterceptorRegistry(), Collections.emptyMap());
      assertEquals(Collections.singletonList("allowed"), names(loaded));
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  /** A loader whose SPI file names exactly the plugins it was given. */
  private static ClassLoader plugins(WeaverPlugin... offered) {
    StringBuilder spi = new StringBuilder();
    for (WeaverPlugin plugin : offered) {
      spi.append(plugin.getClass().getName()).append('\n');
    }
    byte[] bytes = spi.toString().getBytes(StandardCharsets.UTF_8);
    ClassLoader parent = PluginLoaderSpiFilterTest.class.getClassLoader();
    return new ClassLoader(parent) {
      @Override
      public Enumeration<URL> getResources(String name) throws java.io.IOException {
        if (!"META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin"
            .equals(name)) {
          return parent.getResources(name);
        }
        // ServiceLoader reads the provider list through getResources, so this is the only place a
        // test can decide which plugins it discovers.
        URL url =
            new URL(
                null,
                "mem:spi",
                new URLStreamHandler() {
                  @Override
                  protected URLConnection openConnection(URL opened) {
                    return new URLConnection(opened) {
                      @Override
                      public void connect() {}

                      @Override
                      public java.io.InputStream getInputStream() {
                        return new ByteArrayInputStream(bytes);
                      }
                    };
                  }
                });
        return Collections.enumeration(Collections.singletonList(url));
      }
    };
  }

  private static List<String> names(List<WeaverPlugin> plugins) {
    List<String> names = new ArrayList<>();
    for (WeaverPlugin plugin : plugins) {
      names.add(plugin.name());
    }
    return names;
  }

  public static class Allowed implements WeaverPlugin {
    @Override
    public String name() {
      return "allowed";
    }

    @Override
    public void init(PluginContext context) {}

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {}
  }

  public static class Blocked implements WeaverPlugin {
    @Override
    public String name() {
      return "blocked";
    }

    @Override
    public void init(PluginContext context) {}

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {}
  }

  public static class Other implements WeaverPlugin {
    @Override
    public String name() {
      return "other";
    }

    @Override
    public void init(PluginContext context) {}

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {}
  }

  /** Enabled by default, then switches itself off once it can see its config. */
  public static class SwitchedOff implements WeaverPlugin {
    @Override
    public String name() {
      return "switched-off";
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return false;
    }

    @Override
    public void init(PluginContext context) {}

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {}
  }

  /** Fails while initializing, which the loader records and then moves past. */
  public static class Exploding implements WeaverPlugin {
    @Override
    public String name() {
      return "exploding";
    }

    @Override
    public void init(PluginContext context) {
      throw new IllegalStateException("cannot start");
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {}
  }
}
