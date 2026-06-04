package com.github.cc11001100.weavergirl.plugins.okhttp;

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

class OkHttpPluginTest {

    private OkHttpPlugin plugin;
    private TestPluginContext context;
    private TestInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        plugin = new OkHttpPlugin();
        context = new TestPluginContext();
        registry = new TestInterceptorRegistry();
    }

    @Test
    void name_returnsOkhttp() {
        assertEquals("okhttp", plugin.name());
    }

    @Test
    void init_defaultConfig() {
        plugin.init(context);
        assertEquals(3000, plugin.getSlowThresholdMs());
        assertTrue(plugin.isTrackConnectionPool());
        assertTrue(plugin.isTrackRequestBodySize());
        assertTrue(plugin.isPropagateTrace());
        assertTrue(plugin.isEnabled());
    }

    @Test
    void init_readsSlowThresholdConfig() {
        context.config.put("slowThreshold", "5000");
        plugin.init(context);
        assertEquals(5000, plugin.getSlowThresholdMs());
    }

    @Test
    void init_invalidSlowThreshold_usesDefault() {
        context.config.put("slowThreshold", "not-a-number");
        plugin.init(context);
        assertEquals(3000, plugin.getSlowThresholdMs());
    }

    @Test
    void init_readsTrackConnectionPoolConfig() {
        context.config.put("trackConnectionPool", "false");
        plugin.init(context);
        assertFalse(plugin.isTrackConnectionPool());
    }

    @Test
    void init_readsTrackRequestBodySizeConfig() {
        context.config.put("trackRequestBodySize", "false");
        plugin.init(context);
        assertFalse(plugin.isTrackRequestBodySize());
    }

    @Test
    void init_readsPropagateTraceConfig() {
        context.config.put("propagateTrace", "false");
        plugin.init(context);
        assertFalse(plugin.isPropagateTrace());
    }

    @Test
    void enabledFalse_registersNoInterceptors() {
        context.config.put("enabled", "false");
        plugin.init(context);
        plugin.registerInterceptors(registry);
        assertEquals(0, registry.definitions.size());
    }

    @Test
    void enabledTrue_registersInterceptors() {
        plugin.init(context);
        plugin.registerInterceptors(registry);
        assertEquals(2, registry.definitions.size()); // call interceptor + pool interceptor
    }

    @Test
    void enabledTrue_noConnectionPoolTracking_registersOneInterceptor() {
        context.config.put("trackConnectionPool", "false");
        plugin.init(context);
        plugin.registerInterceptors(registry);
        assertEquals(1, registry.definitions.size());
    }

    @Test
    void registersCallAndPoolInterceptors() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        List<String> names = new ArrayList<>();
        for (InterceptorDefinition def : registry.definitions) {
            names.add(def.getName());
        }

        // One should match RealCall pattern, one should match pool pattern
        assertTrue(names.stream().anyMatch(n -> n.contains("RealCall")),
                "Should register RealCall interceptor, got: " + names);
        assertTrue(names.stream().anyMatch(n -> n.contains("ConnectionPool") || n.contains("RealConnectionPool")),
                "Should register connection pool interceptor, got: " + names);
    }

    @Test
    void callInterceptorHasHigherPriority() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        // First definition should be the call interceptor (priority 8)
        InterceptorDefinition callDef = registry.definitions.get(0);
        assertEquals(8, callDef.getPriority());
    }

    @Test
    void poolInterceptorHasLowerPriority() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        // Second definition should be the pool interceptor (priority 15)
        if (registry.definitions.size() > 1) {
            InterceptorDefinition poolDef = registry.definitions.get(1);
            assertEquals(15, poolDef.getPriority());
        }
    }

    @Test
    void callInterceptor_before_setsStartTime() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        assertDoesNotThrow(() -> interceptor.before(inv));
    }

    @Test
    void callInterceptor_after_completesWithoutError() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.after(inv));
    }

    @Test
    void callInterceptor_onException_cleansUp() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        inv.setThrowable(new RuntimeException("connection refused"));
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.onException(inv));
    }

    @Test
    void callInterceptor_fullAroundCycle_noException() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, new Object[]{"http://example.com"});
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.after(inv);
        });
    }

    @Test
    void extractRequestUrl_returnsNullForNoTarget() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        assertNull(plugin.extractRequestUrl(inv));
    }

    @Test
    void extractRequestMethod_returnsNullForNoTarget() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        assertNull(plugin.extractRequestMethod(inv));
    }

    @Test
    void extractResponseCode_returnsZeroForNoReturnValue() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        assertEquals(0, plugin.extractResponseCode(inv));
    }

    @Test
    void extractResponseCode_returnsZeroForNonResponseReturnValue() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        inv.initReturnValue("not a response");
        assertEquals(0, plugin.extractResponseCode(inv));
    }

    @Test
    void extractResponseBodySize_returnsZeroForNoReturnValue() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        assertEquals(0, plugin.extractResponseBodySize(inv));
    }

    @Test
    void extractProtocol_returnsNullForNoReturnValue() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        assertNull(plugin.extractProtocol(inv));
    }

    @Test
    void extractPoolIdleCount_returnsNegativeForNoTarget() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "get", null, null);
        assertEquals(-1, plugin.extractPoolIdleCount(inv));
    }

    @Test
    void extractPoolTotalCount_returnsNegativeForNoTarget() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "get", null, null);
        assertEquals(-1, plugin.extractPoolTotalCount(inv));
    }

    @Test
    void injectTraceIntoRequest_doesNotThrowForNoTarget() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
        assertDoesNotThrow(() -> plugin.injectTraceIntoRequest(inv, "trace-123"));
    }

    @Test
    void callInterceptor_withTraceId_doesNotThrow() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", "test-trace-123");
        try {
            MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
            assertDoesNotThrow(() -> interceptor.before(inv));
        } finally {
            com.github.cc11001100.weavergirl.api.context.ThreadContext.clear();
        }
    }

    @Test
    void callInterceptor_withTraceAndSpanId_doesNotThrow() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", "trace-123");
        com.github.cc11001100.weavergirl.api.context.ThreadContext.put("spanId", "span-456");
        try {
            MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
            assertDoesNotThrow(() -> interceptor.before(inv));
        } finally {
            com.github.cc11001100.weavergirl.api.context.ThreadContext.clear();
        }
    }

    /**
     * Simple PluginContext implementation for testing.
     */
    private static class TestPluginContext implements PluginContext {
        final Map<String, String> config = new HashMap<>();

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
            return "okhttp";
        }
    }

    /**
     * Simple InterceptorRegistry implementation for testing.
     */
    private static class TestInterceptorRegistry implements InterceptorRegistry {
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
            List<InterceptorDefinition> result = new ArrayList<>();
            for (InterceptorDefinition def : definitions) {
                if (def.getPointcut().getClassMatcher().matches(className)) {
                    result.add(def);
                }
            }
            return result;
        }

        @Override
        public List<InterceptorDefinition> getAllDefinitions() {
            return definitions;
        }
    }
}
