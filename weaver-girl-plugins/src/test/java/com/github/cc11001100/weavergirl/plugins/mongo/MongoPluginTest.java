package com.github.cc11001100.weavergirl.plugins.mongo;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MongoPluginTest {

    private MongoPlugin plugin;
    private TestPluginContext context;
    private TestInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        plugin = new MongoPlugin();
        context = new TestPluginContext();
        registry = new TestInterceptorRegistry();
    }

    @Test
    void name_returnsMongo() {
        assertEquals("mongo", plugin.name());
    }

    @Test
    void init_defaultConfig() {
        plugin.init(context);
        assertEquals(500, plugin.getSlowThresholdMs());
        assertTrue(plugin.isLogCollection());
        assertEquals(100, plugin.getMaxDocLength());
        assertTrue(plugin.isEnabled());
    }

    @Test
    void init_readsSlowThreshold() {
        context.config.put("slowThreshold", "2000");
        plugin.init(context);
        assertEquals(2000, plugin.getSlowThresholdMs());
    }

    @Test
    void init_invalidSlowThreshold_usesDefault() {
        context.config.put("slowThreshold", "abc");
        plugin.init(context);
        assertEquals(500, plugin.getSlowThresholdMs());
    }

    @Test
    void init_readsLogCollection() {
        context.config.put("logCollection", "false");
        plugin.init(context);
        assertFalse(plugin.isLogCollection());
    }

    @Test
    void init_readsMaxDocLength() {
        context.config.put("maxDocLength", "200");
        plugin.init(context);
        assertEquals(200, plugin.getMaxDocLength());
    }

    @Test
    void init_invalidMaxDocLength_usesDefault() {
        context.config.put("maxDocLength", "xyz");
        plugin.init(context);
        assertEquals(100, plugin.getMaxDocLength());
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
        assertEquals(3, registry.definitions.size());
    }

    @Test
    void registersThreeInterceptors_clientCollectionDatabase() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        List<String> names = new ArrayList<>();
        for (InterceptorDefinition def : registry.definitions) {
            names.add(def.getName());
        }

        // Verify all three target classes are covered
        assertTrue(names.stream().anyMatch(n -> n.contains("MongoClientImpl")));
        assertTrue(names.stream().anyMatch(n -> n.contains("MongoCollectionImpl")));
        assertTrue(names.stream().anyMatch(n -> n.contains("MongoDatabaseImpl")));
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
    void interceptor_beforeAndAfter_completesWithoutError() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        MethodInvocation inv = new MethodInvocation(
            com.github.cc11001100.weavergirl.plugins.mongo.MongoPluginTest.FakeMongoClient.class,
            "startSession", null, null);
        assertDoesNotThrow(() -> {
            def.getInterceptor().before(inv);
            def.getInterceptor().after(inv);
        });
    }

    @Test
    void interceptor_onException_cleansUpAndLogs() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        MethodInvocation inv = new MethodInvocation(
            com.github.cc11001100.weavergirl.plugins.mongo.MongoPluginTest.FakeMongoClient.class,
            "startSession", null, null);
        inv.setThrowable(new RuntimeException("connection refused"));
        assertDoesNotThrow(() -> def.getInterceptor().onException(inv));
    }

    @Test
    void extractCollectionLabel_collectionOperation() {
        plugin.init(context);
        // Simulate MongoCollectionImpl
        MethodInvocation inv = new MethodInvocation(
            com.github.cc11001100.weavergirl.plugins.mongo.MongoPluginTest.FakeMongoCollection.class,
            "find", null, null);
        String label = plugin.extractCollectionLabel(inv);
        assertTrue(label.contains("op=find"));
    }

    @Test
    void extractCollectionLabel_databaseOperation() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(
            com.github.cc11001100.weavergirl.plugins.mongo.MongoPluginTest.FakeMongoDatabase.class,
            "createCollection", null, null);
        String label = plugin.extractCollectionLabel(inv);
        assertTrue(label.contains("db-op=createCollection"));
    }

    @Test
    void extractDocPreview_returnsFirstArgToString() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "insertOne", null,
            new Object[]{"{_id: 1, name: 'test'}"});
        String preview = plugin.extractDocPreview(inv);
        assertNotNull(preview);
        assertTrue(preview.contains("_id"));
    }

    @Test
    void extractDocPreview_truncatesLongDocument() {
        plugin.init(context);
        StringBuilder longDoc = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longDoc.append("x");
        }
        MethodInvocation inv = new MethodInvocation(Object.class, "insertOne", null,
            new Object[]{longDoc.toString()});
        String preview = plugin.extractDocPreview(inv);
        assertNotNull(preview);
        assertTrue(preview.endsWith("..."));
        assertTrue(preview.length() <= 100 + 3); // maxDocLength + "..."
    }

    @Test
    void extractDocPreview_nullArguments_returnsNull() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "find", null, null);
        String preview = plugin.extractDocPreview(inv);
        assertNull(preview);
    }

    @Test
    void extractDocPreview_nullFirstArgument_returnsNull() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "find", null,
            new Object[]{null});
        String preview = plugin.extractDocPreview(inv);
        assertNull(preview);
    }

    // Fake class stubs for simulating MongoDB internal classes
    private static class FakeMongoClient {}
    private static class FakeMongoCollection {}
    private static class FakeMongoDatabase {}

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
            return Collections.unmodifiableMap(config);
        }

        @Override
        public String getPluginName() {
            return "mongo";
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
            return Collections.unmodifiableList(definitions);
        }
    }
}