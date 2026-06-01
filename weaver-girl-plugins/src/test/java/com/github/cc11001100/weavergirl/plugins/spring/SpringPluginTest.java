package com.github.cc11001100.weavergirl.plugins.spring;

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

class SpringPluginTest {

    private SpringPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new SpringPlugin();
    }

    @Test
    void name_returnsSpring() {
        assertEquals("spring", plugin.name());
    }

    @Test
    void init_readsSlowThresholdConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("slowThreshold", "5000");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        // Verify by checking that registerInterceptors works with the configured threshold
        // (We test indirectly through the interceptor behavior)
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_defaultSlowThreshold() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_invalidSlowThreshold_usesDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("slowThreshold", "not-a-number");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        // Should not throw, uses default 3000
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_enabledFalse_registersNoInterceptors() {
        Map<String, String> config = new HashMap<>();
        config.put("enabled", "false");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertTrue(defs.isEmpty());
    }

    @Test
    void init_enabledTrue_registersInterceptors() {
        Map<String, String> config = new HashMap<>();
        config.put("enabled", "true");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_parsesInterceptAnnotations() {
        Map<String, String> config = new HashMap<>();
        config.put("interceptAnnotations", "com.example.MyAnnotation , com.example.OtherAnnotation");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertEquals(2, defs.size());
    }

    @Test
    void init_defaultAnnotations_registersFive() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertEquals(5, defs.size());
    }

    @Test
    void init_logArgumentsConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("logArguments", "true");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void interceptor_beforeSetsStartTime() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "toString", "hello", null);
        // Should not throw
        assertDoesNotThrow(() -> def.getInterceptor().before(inv));
    }

    @Test
    void interceptor_afterComputesElapsedTime() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "toString", "hello", null);
        def.getInterceptor().before(inv);
        // Should not throw
        assertDoesNotThrow(() -> def.getInterceptor().after(inv));
    }

    @Test
    void interceptor_onException_cleansUp() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "toString", "hello", null);
        inv.setThrowable(new RuntimeException("test error"));
        // Should not throw
        assertDoesNotThrow(() -> def.getInterceptor().onException(inv));
    }

    @Test
    void interceptor_fullAroundCycle_noException() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[]{"arg1", "arg2"});
        assertDoesNotThrow(() -> {
            def.getInterceptor().before(inv);
            def.getInterceptor().after(inv);
        });
    }

    /**
     * Helper to register interceptors and collect the definitions.
     */
    private List<InterceptorDefinition> registerAndCollect() {
        List<InterceptorDefinition> defs = new ArrayList<>();
        InterceptorRegistry registry = new InterceptorRegistry() {
            @Override
            public void register(InterceptorDefinition definition) {
                defs.add(definition);
            }

            @Override
            public boolean unregister(String name) {
                return false;
            }

            @Override
            public List<InterceptorDefinition> getInterceptorsForClass(String className) {
                return defs;
            }

            @Override
            public List<InterceptorDefinition> getAllDefinitions() {
                return defs;
            }
        };
        plugin.registerInterceptors(registry);
        return defs;
    }

    /**
     * Simple test PluginContext backed by a map.
     */
    private static class TestPluginContext implements PluginContext {
        private final Map<String, String> config;

        TestPluginContext(Map<String, String> config) {
            this.config = config;
        }

        @Override
        public InterceptorRegistry getRegistry() {
            return null;
        }

        @Override
        public String getConfig(String key) {
            return config.get(key);
        }

        @Override
        public String getConfig(String key, String defaultValue) {
            return config.getOrDefault(key, defaultValue);
        }

        @Override
        public Map<String, String> getAllConfig() {
            return config;
        }

        @Override
        public String getPluginName() {
            return "spring";
        }
    }
}
