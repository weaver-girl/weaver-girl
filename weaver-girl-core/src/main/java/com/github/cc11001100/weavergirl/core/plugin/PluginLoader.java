package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.WeaverGirl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Loads WeaverPlugin implementations via Java ServiceLoader mechanism.
 * Manages the full plugin lifecycle: init → registerInterceptors → (runtime) → destroy.
 */
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final List<WeaverPlugin> loadedPlugins = new ArrayList<>();

    /**
     * Load all plugins from the given ClassLoader and register their interceptors.
     * Calls init() then registerInterceptors() on each plugin.
     *
     * @param classLoader the ClassLoader to scan for plugin SPI declarations
     * @param registry the interceptor registry
     * @param config agent configuration properties (may be empty)
     * @return the list of successfully loaded plugins
     */
    public List<WeaverPlugin> loadPlugins(ClassLoader classLoader, InterceptorRegistry registry,
                                          Map<String, String> config) {
        ServiceLoader<WeaverPlugin> serviceLoader = ServiceLoader.load(WeaverPlugin.class, classLoader);

        // Collect all discovered plugins first
        List<WeaverPlugin> discovered = new ArrayList<>();
        for (WeaverPlugin plugin : serviceLoader) {
            discovered.add(plugin);
        }

        // Resolve dependencies before initializing
        PluginDependencyResolver resolver = new PluginDependencyResolver();
        List<WeaverPlugin> sorted = resolver.resolve(discovered);

        for (WeaverPlugin plugin : sorted) {
            try {
                log.info("Loading plugin: {}", plugin.name());

                // Phase 1: init
                PluginContext context = new DefaultPluginContext(registry, config, plugin.name());
                plugin.init(context);

                // Phase 2: register interceptors
                plugin.registerInterceptors(registry);

                loadedPlugins.add(plugin);
                log.info("Plugin {} loaded successfully", plugin.name());
            } catch (Throwable e) {
                log.error("Failed to load plugin {}: {}", plugin.name(), e.getMessage(), e);
            }
        }

        log.info("Loaded {} plugins total", loadedPlugins.size());
        return Collections.unmodifiableList(loadedPlugins);
    }

    /**
     * Load all plugins using the context ClassLoader.
     *
     * @see #loadPlugins(ClassLoader, InterceptorRegistry, Map)
     */
    public List<WeaverPlugin> loadPlugins(InterceptorRegistry registry, Map<String, String> config) {
        return loadPlugins(Thread.currentThread().getContextClassLoader(), registry, config);
    }

    /**
     * Load plugins from a plugin directory, creating an isolated ClassLoader per JAR.
     * This is the preferred way to load plugins in production.
     *
     * @param pluginDir the directory containing plugin JARs
     * @param registry the interceptor registry
     * @param config agent configuration
     * @return the list of successfully loaded plugins
     */
    public List<WeaverPlugin> loadPluginsFromDirectory(String pluginDir,
            InterceptorRegistry registry, Map<String, String> config) {
        PluginJarScanner scanner = new PluginJarScanner();
        List<PluginClassLoader> classLoaders = scanner.scan(pluginDir,
                WeaverGirl.class.getClassLoader());

        List<WeaverPlugin> allPlugins = new ArrayList<>();
        for (PluginClassLoader cl : classLoaders) {
            List<WeaverPlugin> plugins = loadPlugins(cl, registry, config);
            allPlugins.addAll(plugins);
        }
        return allPlugins;
    }

    /**
     * Destroy all loaded plugins in reverse order.
     * Should be called during agent shutdown.
     */
    public void destroyAll() {
        // Destroy in reverse order of loading
        for (int i = loadedPlugins.size() - 1; i >= 0; i--) {
            WeaverPlugin plugin = loadedPlugins.get(i);
            try {
                plugin.destroy();
                log.info("Plugin {} destroyed", plugin.name());
            } catch (Exception e) {
                log.error("Failed to destroy plugin {}: {}", plugin.name(), e.getMessage(), e);
            }
        }
        loadedPlugins.clear();
    }

    /**
     * Get all currently loaded plugins.
     */
    public List<WeaverPlugin> getLoadedPlugins() {
        return Collections.unmodifiableList(loadedPlugins);
    }
}
