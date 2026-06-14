package com.github.cc11001100.weavergirl.plugins.elasticsearch;

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

class ElasticsearchPluginTest {

    private ElasticsearchPlugin plugin;
    private TestPluginContext context;
    private TestInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        plugin = new ElasticsearchPlugin();
        context = new TestPluginContext();
        registry = new TestInterceptorRegistry();
    }

    @Test
    void name_returnsElasticsearch() {
        assertEquals("elasticsearch", plugin.name());
    }

    @Test
    void init_defaultConfig() {
        plugin.init(context);
        assertEquals(3000, plugin.getSlowQueryThresholdMs());
        assertTrue(plugin.isTrackBulkSize());
        assertTrue(plugin.isPropagateTrace());
        assertTrue(plugin.isEnabled());
    }

    @Test
    void init_readsSlowQueryThresholdConfig() {
        context.config.put("slowQueryThreshold", "5000");
        plugin.init(context);
        assertEquals(5000, plugin.getSlowQueryThresholdMs());
    }

    @Test
    void init_invalidSlowQueryThreshold_usesDefault() {
        context.config.put("slowQueryThreshold", "not-a-number");
        plugin.init(context);
        assertEquals(3000, plugin.getSlowQueryThresholdMs());
    }

    @Test
    void init_readsTrackBulkSizeConfig() {
        context.config.put("trackBulkSize", "false");
        plugin.init(context);
        assertFalse(plugin.isTrackBulkSize());
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
    void enabledTrue_registersThreeInterceptors() {
        plugin.init(context);
        plugin.registerInterceptors(registry);
        // RestHighLevelClient + Java client + RestClient = 3
        assertEquals(3, registry.definitions.size());
    }

    @Test
    void registersHighLevelJavaAndLowLevelInterceptors() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        List<String> names = new ArrayList<>();
        for (InterceptorDefinition def : registry.definitions) {
            names.add(def.getName());
        }
        // Should cover RestHighLevelClient, Java client, and RestClient
        assertTrue(names.size() >= 3, "Should register 3 interceptors, got: " + names);
    }

    @Test
    void highLevelInterceptor_hasPriority10() {
        plugin.init(context);
        plugin.registerInterceptors(registry);
        // First two should have priority 10
        assertEquals(10, registry.definitions.get(0).getPriority());
        assertEquals(10, registry.definitions.get(1).getPriority());
    }

    @Test
    void lowLevelInterceptor_hasPriority15() {
        plugin.init(context);
        plugin.registerInterceptors(registry);
        // RestClient interceptor has lower priority
        assertEquals(15, registry.definitions.get(2).getPriority());
    }

    @Test
    void highLevelInterceptor_before_after_noException() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.after(inv);
        });
    }

    @Test
    void highLevelInterceptor_onException_cleansUp() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
        inv.setThrowable(new RuntimeException("index not found"));
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.onException(inv);
        });
    }

    @Test
    void lowLevelInterceptor_before_after_noException() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(2);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "performRequest", null, null);
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.after(inv);
        });
    }

    @Test
    void lowLevelInterceptor_onException_cleansUp() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(2);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "performRequest", null, null);
        inv.setThrowable(new RuntimeException("timeout"));
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.onException(inv);
        });
    }

    @Test
    void extractIndexName_returnsNullForNoArgs() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
        assertNull(plugin.extractIndexName(inv));
    }

    @Test
    void extractIndexName_returnsNullForNonRequestArg() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, new Object[]{42});
        assertNull(plugin.extractIndexName(inv));
    }

    @Test
    void extractHitCount_returnsNegativeForNoReturn() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
        assertEquals(-1, plugin.extractHitCount(inv));
    }

    @Test
    void extractHitCount_returnsNegativeForNonResponseReturn() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
        inv.initReturnValue("not a response");
        assertEquals(-1, plugin.extractHitCount(inv));
    }

    @Test
    void extractTookMs_returnsNegativeForNoReturn() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
        assertEquals(-1, plugin.extractTookMs(inv));
    }

    @Test
    void extractBulkSize_returnsNegativeForNoArgs() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "bulk", null, null);
        assertEquals(-1, plugin.extractBulkSize(inv));
    }

    @Test
    void extractBulkSize_returnsNegativeForNonRequestArg() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "bulk", null, new Object[]{"string"});
        assertEquals(-1, plugin.extractBulkSize(inv));
    }

    @Test
    void extractEndpoint_returnsNullForNoArgs() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "performRequest", null, null);
        assertNull(plugin.extractEndpoint(inv));
    }

    @Test
    void extractStatusCode_returnsZeroForNoReturn() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "performRequest", null, null);
        assertEquals(0, plugin.extractStatusCode(inv));
    }

    @Test
    void injectTraceHeaders_doesNotThrowForNoArgs() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
        assertDoesNotThrow(() -> plugin.injectTraceHeaders(inv));
    }

    @Test
    void highLevelInterceptor_withTraceId_doesNotThrow() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", "trace-123");
        com.github.cc11001100.weavergirl.api.context.ThreadContext.put("spanId", "span-456");
        try {
            MethodInvocation inv = new MethodInvocation(Object.class, "search", null, null);
            assertDoesNotThrow(() -> interceptor.before(inv));
        } finally {
            com.github.cc11001100.weavergirl.api.context.ThreadContext.clear();
        }
    }

    @Test
    void highLevelInterceptor_fullLifecycle_search() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "search", null, new Object[]{"request"});
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            inv.initReturnValue("response");
            interceptor.after(inv);
        });
    }

    @Test
    void highLevelInterceptor_fullLifecycle_bulk() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "bulk", null, new Object[]{"bulkRequest"});
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            inv.initReturnValue("bulkResponse");
            interceptor.after(inv);
        });
    }

    /**
     * Simple PluginContext implementation for testing.
     */
    private static class TestPluginContext implements PluginContext {
        final Map<String, String> config = new HashMap<>();

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
        public String getPluginName() { return "elasticsearch"; }
    }

    /**
     * Simple InterceptorRegistry implementation for testing.
     */
    private static class TestInterceptorRegistry implements InterceptorRegistry {
        final List<InterceptorDefinition> definitions = new ArrayList<>();

        @Override
        public void register(InterceptorDefinition definition) { definitions.add(definition); }

        @Override
        public boolean unregister(String name) { return definitions.removeIf(d -> d.getName().equals(name)); }

        @Override
        public List<InterceptorDefinition> getInterceptorsForClass(String className) {
            List<InterceptorDefinition> result = new ArrayList<>();
            for (InterceptorDefinition def : definitions) {
                if (def.getPointcut().getClassMatcher().matches(className)) result.add(def);
            }
            return result;
        }

        @Override
        public List<InterceptorDefinition> getAllDefinitions() { return definitions; }
    }
}
