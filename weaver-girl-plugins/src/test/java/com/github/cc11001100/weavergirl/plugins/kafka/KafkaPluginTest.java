package com.github.cc11001100.weavergirl.plugins.kafka;

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

class KafkaPluginTest {

    private KafkaPlugin plugin;
    private TestPluginContext context;
    private TestInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        plugin = new KafkaPlugin();
        context = new TestPluginContext();
        registry = new TestInterceptorRegistry();
    }

    @Test
    void name_returnsKafka() {
        assertEquals("kafka", plugin.name());
    }

    @Test
    void init_defaultConfig() {
        plugin.init(context);
        assertEquals(1000, plugin.getSlowThresholdMs());
        assertTrue(plugin.isLogTopic());
        assertTrue(plugin.isEnabled());
    }

    @Test
    void init_readsSlowThresholdConfig() {
        context.config.put("slowThreshold", "3000");
        plugin.init(context);
        assertEquals(3000, plugin.getSlowThresholdMs());
    }

    @Test
    void init_invalidSlowThreshold_usesDefault() {
        context.config.put("slowThreshold", "not-a-number");
        plugin.init(context);
        assertEquals(1000, plugin.getSlowThresholdMs());
    }

    @Test
    void init_readsLogTopicConfig() {
        context.config.put("logTopic", "false");
        plugin.init(context);
        assertFalse(plugin.isLogTopic());
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
        assertEquals(2, registry.definitions.size());
    }

    @Test
    void registersProducerAndConsumerInterceptors() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        List<String> names = new ArrayList<>();
        for (InterceptorDefinition def : registry.definitions) {
            names.add(def.getName());
        }

        assertTrue(names.stream().anyMatch(n -> n.contains("KafkaProducer")));
        assertTrue(names.stream().anyMatch(n -> n.contains("KafkaConsumer")));
    }

    @Test
    void allInterceptorsHavePriority10() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        for (InterceptorDefinition def : registry.definitions) {
            assertEquals(10, def.getPriority());
        }
    }

    @Test
    void producerInterceptor_before_setsStartTime() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "send", null, null);
        assertDoesNotThrow(() -> interceptor.before(inv));
    }

    @Test
    void producerInterceptor_after_completesWithoutError() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "send", null, null);
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.after(inv));
    }

    @Test
    void producerInterceptor_onException_cleansUp() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "send", null, null);
        inv.setThrowable(new RuntimeException("broker unavailable"));
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.onException(inv));
    }

    @Test
    void consumerInterceptor_beforeAndAfter_completesWithoutError() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        // Get the consumer interceptor (second one)
        InterceptorDefinition def = registry.definitions.get(1);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "poll", null, null);
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.after(inv);
        });
    }

    @Test
    void consumerInterceptor_onException_cleansUp() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(1);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "poll", null, null);
        inv.setThrowable(new RuntimeException("consumer error"));
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.onException(inv));
    }

    @Test
    void extractTopic_returnsNullForNoArguments() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "send", null, null);
        assertNull(plugin.extractTopic(inv));
    }

    @Test
    void extractTopic_returnsNullForNonProducerRecordArgument() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "send", null, new Object[]{42});
        assertNull(plugin.extractTopic(inv));
    }

    @Test
    void extractTopicFromConsumerRecords_returnsNullForNoReturnValue() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "poll", null, null);
        assertNull(plugin.extractTopicFromConsumerRecords(inv));
    }

    @Test
    void extractTopicFromConsumerRecords_returnsNullForNonIterableReturnValue() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "poll", null, null);
        inv.initReturnValue("not records");
        assertNull(plugin.extractTopicFromConsumerRecords(inv));
    }

    @Test
    void injectTraceHeader_doesNotThrowForNoArguments() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "send", null, null);
        assertDoesNotThrow(() -> plugin.injectTraceHeader(inv, "trace-123"));
    }

    @Test
    void injectTraceHeader_doesNotThrowForNonProducerRecordArgument() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "send", null, new Object[]{42});
        assertDoesNotThrow(() -> plugin.injectTraceHeader(inv, "trace-123"));
    }

    @Test
    void producerInterceptor_before_withTraceId_doesNotThrow() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", "test-trace-123");
        try {
            MethodInvocation inv = new MethodInvocation(Object.class, "send", null, null);
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
            return "kafka";
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
