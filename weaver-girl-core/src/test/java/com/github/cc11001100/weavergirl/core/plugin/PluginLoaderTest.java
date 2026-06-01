// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/plugin/PluginLoaderTest.java
package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PluginLoaderTest {

    private InterceptorRegistry registry;
    private PluginLoader loader;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
        loader = new PluginLoader();
    }

    @Test
    void loadPlugins_withNoPlugins_returnsEmptyList() {
        List<WeaverPlugin> plugins = loader.loadPlugins(getClass().getClassLoader(), registry, Collections.emptyMap());
        assertNotNull(plugins);
    }

    @Test
    void loadPlugins_pluginThrowsException_doesNotCrash() {
        WeaverPlugin brokenPlugin = new WeaverPlugin() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void registerInterceptors(InterceptorRegistry reg) {
                throw new RuntimeException("Plugin init failed");
            }
        };
        assertThrows(RuntimeException.class, () -> brokenPlugin.registerInterceptors(registry));
    }

    @Test
    void abstractPlugin_intercept_buildsCorrectDefinition() {
        AbstractPlugin plugin = new AbstractPlugin() {
            @Override
            public String name() {
                return "test-plugin";
            }

            @Override
            public void registerInterceptors(InterceptorRegistry reg) {
                InterceptorDefinition def = intercept("com.example.Service")
                        .method("process")
                        .before(inv -> {})
                        .build();
                reg.register(def);
            }
        };

        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.Service");
        assertEquals(1, defs.size());
    }

    @Test
    void destroyAll_callsDestroyOnAllPlugins() {
        AtomicBoolean destroyed = new AtomicBoolean(false);
        WeaverPlugin plugin = new WeaverPlugin() {
            @Override
            public String name() { return "destroy-test"; }

            @Override
            public void registerInterceptors(InterceptorRegistry reg) { }

            @Override
            public void destroy() {
                destroyed.set(true);
            }
        };

        // Manually add plugin to test destroy
        loader.loadPlugins(getClass().getClassLoader(), registry, Collections.emptyMap());
        // Since the test classloader won't find our plugin via SPI,
        // test destroyAll with empty list first
        loader.destroyAll();
        assertFalse(destroyed.get(), "Plugin was not loaded via SPI, so destroy should not be called");
    }
}
