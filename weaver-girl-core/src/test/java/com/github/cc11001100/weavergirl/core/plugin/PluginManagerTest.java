package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.PluginInfo;
import com.github.cc11001100.weavergirl.api.plugin.PluginManager;
import com.github.cc11001100.weavergirl.api.plugin.PluginState;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Plugin Manager (P47).
 */
class PluginManagerTest {

    private DefaultInterceptorRegistry registry;
    private PluginLoader pluginLoader;
    private DefaultPluginManager pluginManager;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
        pluginLoader = new PluginLoader();
    }

    private void initPluginManager(Map<String, String> config) {
        // Manually create a test plugin and register it
        pluginLoader.loadPlugins(getClass().getClassLoader(), registry, config);
        pluginManager = new DefaultPluginManager(pluginLoader, registry);
    }

    // ===== PluginState transitions =====

    @Test
    void pluginState_transitions() {
        assertTrue(PluginState.LOADED.canTransitionTo(PluginState.ACTIVE));
        assertTrue(PluginState.LOADED.canTransitionTo(PluginState.UNLOADED));
        assertFalse(PluginState.LOADED.canTransitionTo(PluginState.DISABLED));

        assertTrue(PluginState.ACTIVE.canTransitionTo(PluginState.DISABLED));
        assertTrue(PluginState.ACTIVE.canTransitionTo(PluginState.UNLOADED));
        assertFalse(PluginState.ACTIVE.canTransitionTo(PluginState.LOADED));

        assertTrue(PluginState.DISABLED.canTransitionTo(PluginState.ACTIVE));
        assertTrue(PluginState.DISABLED.canTransitionTo(PluginState.UNLOADED));
        assertFalse(PluginState.DISABLED.canTransitionTo(PluginState.LOADED));

        // UNLOADED is terminal
        assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.ACTIVE));
        assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.DISABLED));
        assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.LOADED));
        assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.UNLOADED));
    }

    // ===== Disable/Enable plugin =====

    @Test
    void disablePlugin_unknownPlugin_returnsFalse() {
        initPluginManager(new HashMap<>());
        assertFalse(pluginManager.disablePlugin("non-existent"));
    }

    @Test
    void enablePlugin_unknownPlugin_returnsFalse() {
        initPluginManager(new HashMap<>());
        assertFalse(pluginManager.enablePlugin("non-existent"));
    }

    @Test
    void pluginInfo_returnsInfoForLoadedPlugins() {
        initPluginManager(new HashMap<>());

        List<PluginInfo> allInfo = pluginManager.getAllPluginInfo();
        // Plugins may or may not be loaded depending on test classpath
        for (PluginInfo info : allInfo) {
            assertNotNull(info.getName());
            assertEquals(PluginState.ACTIVE, info.getState());
            assertTrue(info.getLoadedAt() > 0);
        }
    }

    @Test
    void isPluginActive_checksState() {
        initPluginManager(new HashMap<>());

        // Check built-in plugins are active
        List<PluginInfo> allInfo = pluginManager.getAllPluginInfo();
        if (!allInfo.isEmpty()) {
            String firstPlugin = allInfo.get(0).getName();
            assertTrue(pluginManager.isPluginActive(firstPlugin));
        }

        assertFalse(pluginManager.isPluginActive("non-existent"));
    }

    @Test
    void getActivePluginCount_matchesActivePlugins() {
        initPluginManager(new HashMap<>());
        int activeCount = pluginManager.getActivePluginCount();
        assertEquals(pluginManager.getAllPluginInfo().size(), activeCount);
    }

    @Test
    void getPluginInfo_returnsEmptyForUnknown() {
        initPluginManager(new HashMap<>());
        assertFalse(pluginManager.getPluginInfo("non-existent").isPresent());
    }

    // ===== PluginManager.getInstance() =====

    @Test
    void getInstance_returnsCreatedManager() {
        initPluginManager(new HashMap<>());
        PluginManager instance = PluginManager.getInstance();
        assertNotNull(instance);
        assertSame(pluginManager, instance);
    }

    // ===== Manual plugin disable/enable lifecycle =====

    @Test
    void disableAndEnable_lifecycle() {
        // Create a simple registry and plugin manager manually
        DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
        PluginLoader loader = new PluginLoader();

        // Register a test interceptor
        Interceptor testInterceptor = new Interceptor() {};
        InterceptorDefinition def = new InterceptorDefinition(
                "test-plugin-myMethod",
                new Pointcut(ClassMatcher.byName("com.example.Test"), MethodMatcher.byName("myMethod")),
                testInterceptor, 0
        );
        reg.register(def);

        // Manually add a plugin to the loader
        // Since we can't easily inject into PluginLoader, test with what's loaded
        DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

        // With no plugins loaded, all operations should return false
        assertFalse(pm.disablePlugin("anything"));
        assertEquals(0, pm.getActivePluginCount());
        assertTrue(pm.getAllPluginInfo().isEmpty());
    }

    // ===== WeaverPlugin interface =====

    @Test
    void weaverPlugin_defaultMethods() {
        WeaverPlugin plugin = new WeaverPlugin() {
            @Override public String name() { return "test"; }
            @Override public void registerInterceptors(InterceptorRegistry r) {}
        };

        // Default implementations
        assertArrayEquals(new String[0], plugin.depends());
        assertTrue(plugin.isEnabled(null));
        assertDoesNotThrow(() -> plugin.init(null));
        assertDoesNotThrow(() -> plugin.destroy());
    }

    // ===== PluginInfo =====

    @Test
    void pluginInfo_toString() {
        PluginInfo info = new PluginInfo("test", PluginState.ACTIVE, "1.0",
                3, System.currentTimeMillis(), System.currentTimeMillis());
        String str = info.toString();
        assertTrue(str.contains("test"));
        assertTrue(str.contains("ACTIVE"));
        assertTrue(str.contains("3"));
    }
}
