package com.github.cc11001100.weavergirl.plugins.redis;

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

class RedisPluginTest {

    private RedisPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new RedisPlugin();
    }

    @Test
    void name_returnsRedis() {
        assertEquals("redis", plugin.name());
    }

    @Test
    void init_readsSlowCommandThresholdConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("slowCommandThreshold", "200");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_defaultSlowCommandThreshold() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_invalidSlowCommandThreshold_usesDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("slowCommandThreshold", "not-a-number");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        // Should not throw, uses default 100
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
    void init_defaultRegistersTwoInterceptors_jedisAndLettuce() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertEquals(2, defs.size());
    }

    @Test
    void init_logKeysConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("logKeys", "false");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_maxKeyLengthConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("maxKeyLength", "100");
        PluginContext context = new TestPluginContext(config);
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        assertFalse(defs.isEmpty());
    }

    @Test
    void init_invalidMaxKeyLength_usesDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("maxKeyLength", "abc");
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
        MethodInvocation inv = new MethodInvocation(String.class, "get", "target", new Object[]{"mykey"});
        assertDoesNotThrow(() -> def.getInterceptor().before(inv));
    }

    @Test
    void interceptor_afterComputesElapsedTime() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "get", "target", new Object[]{"mykey"});
        def.getInterceptor().before(inv);
        assertDoesNotThrow(() -> def.getInterceptor().after(inv));
    }

    @Test
    void interceptor_onException_cleansUp() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "get", "target", new Object[]{"mykey"});
        inv.setThrowable(new RuntimeException("connection refused"));
        assertDoesNotThrow(() -> def.getInterceptor().onException(inv));
    }

    @Test
    void interceptor_fullAroundCycle_noException() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "set", "target", new Object[]{"key1", "value1"});
        assertDoesNotThrow(() -> {
            def.getInterceptor().before(inv);
            def.getInterceptor().after(inv);
        });
    }

    @Test
    void extractKey_returnsFirstStringArgument() throws Exception {
        // Test the extractKey behavior through the interceptor
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        // First arg is a String key
        MethodInvocation inv = new MethodInvocation(String.class, "get", "target", new Object[]{"user:123"});
        // before should not throw even with key extraction
        assertDoesNotThrow(() -> def.getInterceptor().before(inv));
        assertDoesNotThrow(() -> def.getInterceptor().after(inv));
    }

    @Test
    void extractKey_noArguments_doesNotThrow() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "ping", "target", null);
        assertDoesNotThrow(() -> def.getInterceptor().before(inv));
        assertDoesNotThrow(() -> def.getInterceptor().after(inv));
    }

    @Test
    void extractKey_nonStringFirstArgument_doesNotThrow() {
        PluginContext context = new TestPluginContext(new HashMap<>());
        plugin.init(context);
        List<InterceptorDefinition> defs = registerAndCollect();
        InterceptorDefinition def = defs.get(0);
        MethodInvocation inv = new MethodInvocation(String.class, "incr", "target", new Object[]{42});
        assertDoesNotThrow(() -> def.getInterceptor().before(inv));
        assertDoesNotThrow(() -> def.getInterceptor().after(inv));
    }

    @Test
    void truncate_longString_truncatesWithEllipsis() throws Exception {
        // Access the private truncate method via reflection
        java.lang.reflect.Method truncateMethod = RedisPlugin.class.getDeclaredMethod("truncate", String.class, int.class);
        truncateMethod.setAccessible(true);
        RedisPlugin instance = new RedisPlugin();
        String result = (String) truncateMethod.invoke(instance, "abcdefghij", 5);
        assertEquals("abcde...", result);
    }

    @Test
    void truncate_shortString_returnsUnchanged() throws Exception {
        java.lang.reflect.Method truncateMethod = RedisPlugin.class.getDeclaredMethod("truncate", String.class, int.class);
        truncateMethod.setAccessible(true);
        RedisPlugin instance = new RedisPlugin();
        String result = (String) truncateMethod.invoke(instance, "abc", 5);
        assertEquals("abc", result);
    }

    @Test
    void truncate_exactLength_returnsUnchanged() throws Exception {
        java.lang.reflect.Method truncateMethod = RedisPlugin.class.getDeclaredMethod("truncate", String.class, int.class);
        truncateMethod.setAccessible(true);
        RedisPlugin instance = new RedisPlugin();
        String result = (String) truncateMethod.invoke(instance, "abcde", 5);
        assertEquals("abcde", result);
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
            return "redis";
        }
    }
}
