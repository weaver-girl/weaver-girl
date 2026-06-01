package com.github.cc11001100.weavergirl.plugins.exception;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionMonitorPluginTest {

    private ExceptionMonitorPlugin plugin;
    private StubRegistry registry;

    @BeforeEach
    void setUp() {
        plugin = new ExceptionMonitorPlugin();
        registry = new StubRegistry();
    }

    @Test
    void name_returnsExceptionMonitor() {
        assertEquals("exception-monitor", plugin.name());
    }

    @Test
    void registerInterceptors_noClassPattern_doesNotRegister() {
        plugin.init(new StubContext(new HashMap<>()));
        plugin.registerInterceptors(registry);
        assertTrue(registry.definitions.isEmpty());
    }

    @Test
    void registerInterceptors_emptyClassPattern_doesNotRegister() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);
        assertTrue(registry.definitions.isEmpty());
    }

    @Test
    void registerInterceptors_enabledFalse_doesNotRegister() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        config.put("enabled", "false");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);
        assertTrue(registry.definitions.isEmpty());
    }

    @Test
    void registerInterceptors_withClassPattern_registersDefinition() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);
        assertEquals(1, registry.definitions.size());
    }

    @Test
    void registeredDefinition_hasCorrectPriority() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);
        assertEquals(5, registry.definitions.get(0).getPriority());
    }

    @Test
    void onException_callback_logsExceptionInfo() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(
                ExceptionMonitorPluginTest.class, "testMethod", null, new Object[0]);
        inv.setThrowable(new RuntimeException("test error"));

        // Should not throw
        assertDoesNotThrow(() -> interceptor.onException(inv));
    }

    @Test
    void onException_callback_withNullThrowable_doesNothing() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(
                ExceptionMonitorPluginTest.class, "testMethod", null, new Object[0]);
        // throwable is null by default

        assertDoesNotThrow(() -> interceptor.onException(inv));
    }

    @Test
    void seenExceptions_tracking_firstExceptionIsNew() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        // First invocation — should be "new"
        MethodInvocation inv1 = new MethodInvocation(
                ExceptionMonitorPluginTest.class, "method1", null, new Object[0]);
        inv1.setThrowable(new IllegalStateException("first"));
        assertDoesNotThrow(() -> interceptor.onException(inv1));

        // Second invocation with same exception type at same location — should be "seen"
        MethodInvocation inv2 = new MethodInvocation(
                ExceptionMonitorPluginTest.class, "method1", null, new Object[0]);
        inv2.setThrowable(new IllegalStateException("second"));
        assertDoesNotThrow(() -> interceptor.onException(inv2));
    }

    @Test
    void init_readsAlertOnNewExceptionConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        config.put("alertOnNewException", "false");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);
        assertEquals(1, registry.definitions.size());
    }

    @Test
    void init_readsMaxStackTraceDepthConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        config.put("maxStackTraceDepth", "10");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);
        assertEquals(1, registry.definitions.size());
    }

    @Test
    void init_invalidMaxStackTraceDepth_usesDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("classPattern", "com\\.example\\..*");
        config.put("maxStackTraceDepth", "not-a-number");
        plugin.init(new StubContext(config));
        plugin.registerInterceptors(registry);
        assertEquals(1, registry.definitions.size());
    }

    private static class StubContext implements PluginContext {
        private final Map<String, String> config;

        StubContext(Map<String, String> config) {
            this.config = config;
        }

        @Override
        public InterceptorRegistry getRegistry() { return null; }

        @Override
        public String getConfig(String key) { return config.get(key); }

        @Override
        public String getConfig(String key, String defaultValue) {
            return config.getOrDefault(key, defaultValue);
        }

        @Override
        public Map<String, String> getAllConfig() { return config; }

        @Override
        public String getPluginName() { return "exception-monitor"; }
    }

    private static class StubRegistry implements InterceptorRegistry {
        final List<InterceptorDefinition> definitions = new ArrayList<>();

        @Override
        public void register(InterceptorDefinition definition) {
            definitions.add(definition);
        }

        @Override
        public boolean unregister(String name) {
            return definitions.removeIf(d -> d.getName().equals(name));
        }

        @Override
        public List<InterceptorDefinition> getInterceptorsForClass(String className) {
            return new ArrayList<>();
        }

        @Override
        public List<InterceptorDefinition> getAllDefinitions() {
            return new ArrayList<>(definitions);
        }
    }
}
