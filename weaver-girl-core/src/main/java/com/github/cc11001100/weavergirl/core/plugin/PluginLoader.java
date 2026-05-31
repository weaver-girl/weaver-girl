// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/PluginLoader.java
package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Loads WeaverPlugin implementations via Java ServiceLoader mechanism.
 */
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    /**
     * Load all plugins from the given ClassLoader and register their interceptors.
     */
    public List<WeaverPlugin> loadPlugins(ClassLoader classLoader, InterceptorRegistry registry) {
        List<WeaverPlugin> loaded = new ArrayList<>();
        ServiceLoader<WeaverPlugin> serviceLoader = ServiceLoader.load(WeaverPlugin.class, classLoader);

        for (WeaverPlugin plugin : serviceLoader) {
            try {
                log.info("Loading plugin: {}", plugin.name());
                plugin.registerInterceptors(registry);
                loaded.add(plugin);
                log.info("Plugin {} loaded successfully", plugin.name());
            } catch (Exception e) {
                log.error("Failed to load plugin {}: {}", plugin.name(), e.getMessage(), e);
            }
        }

        log.info("Loaded {} plugins total", loaded.size());
        return loaded;
    }
}
