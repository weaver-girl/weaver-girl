package com.github.cc11001100.weavergirl.testkit;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recording {@link InterceptorRegistry} for plugin unit tests.
 *
 * <p>A plugin unit test usually only asks "which definitions did this plugin register?". Before
 * this class every plugin copied a forty-line anonymous registry that merely collected the
 * definitions, and a thirty-line anonymous {@link PluginContext} that read a map. Both now live
 * here, so a plugin test reads:
 *
 * <pre>
 * RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
 * plugin.registerInterceptors(registry);
 * assertEquals(2, registry.size());
 * </pre>
 *
 * <p>Lookups return the whole recorded list rather than filtering by pointcut: the test asserts on
 * what the plugin declared, not on what the engine would later match.
 *
 * @since 1.0.0
 */
public final class RecordingInterceptorRegistry implements InterceptorRegistry {

  private final List<InterceptorDefinition> definitions = new ArrayList<InterceptorDefinition>();

  @Override
  public void register(InterceptorDefinition definition) {
    definitions.add(definition);
  }

  /**
   * Removes every definition with the given name.
   *
   * @param name the definition name to remove
   * @return true when at least one definition was removed
   */
  @Override
  public boolean unregister(String name) {
    boolean removed = false;
    for (int i = definitions.size() - 1; i >= 0; i--) {
      if (definitions.get(i).getName().equals(name)) {
        definitions.remove(i);
        removed = true;
      }
    }
    return removed;
  }

  /**
   * The recorded definitions. Not filtered: a test asserts on what the plugin registered.
   *
   * @param className ignored
   * @return a snapshot of every recorded definition
   */
  @Override
  public List<InterceptorDefinition> getInterceptorsForClass(String className) {
    return new ArrayList<InterceptorDefinition>(definitions);
  }

  /** @return a snapshot of every recorded definition */
  @Override
  public List<InterceptorDefinition> getAllDefinitions() {
    return Collections.unmodifiableList(new ArrayList<InterceptorDefinition>(definitions));
  }

  /** @return the number of recorded definitions */
  public int size() {
    return definitions.size();
  }

  /**
   * A {@link PluginContext} over a plain map, named for the plugin under test.
   *
   * <p>Reads the map through {@link #getConfig(String)}/ {@link #getConfig(String, String)};
   * {@link #getRegistry()} returns {@code null} because a context is only used for configuration
   * here, and {@link #classPresent} reports {@code true} for everything so a plugin's optional-dependency
   * probe does not hide the registration the test is asserting on.
   *
   * @param pluginName the value {@link #getPluginName()} returns (usually the plugin's {@code name()})
   * @param config the configuration map; may be empty, never null
   * @return a new context
   */
  public static PluginContext context(String pluginName, Map<String, String> config) {
    final Map<String, String> source =
        config == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<>(config);
    return new PluginContext() {
      @Override
      public InterceptorRegistry getRegistry() {
        return null;
      }

      @Override
      public String getConfig(String key) {
        return source.get(key);
      }

      @Override
      public String getConfig(String key, String defaultValue) {
        return source.getOrDefault(key, defaultValue);
      }

      @Override
      public Map<String, String> getAllConfig() {
        return source;
      }

      @Override
      public String getPluginName() {
        return pluginName;
      }

      @Override
      public boolean classPresent(ClassLoader classLoader, String className) {
        return true;
      }
    };
  }
}
