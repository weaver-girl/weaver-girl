package com.github.cc11001100.weavergirl.plugins.grpc;

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

class GrpcPluginTest {

    private GrpcPlugin plugin;
    private TestPluginContext context;
    private TestInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        plugin = new GrpcPlugin();
        context = new TestPluginContext();
        registry = new TestInterceptorRegistry();
    }

    @Test
    void name_returnsGrpc() {
        assertEquals("grpc", plugin.name());
    }

    @Test
    void init_defaultConfig() {
        plugin.init(context);
        assertEquals(2000, plugin.getSlowThresholdMs());
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
        assertEquals(2000, plugin.getSlowThresholdMs());
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
    void registersServerAndClientInterceptors() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        List<String> names = new ArrayList<>();
        for (InterceptorDefinition def : registry.definitions) {
            names.add(def.getName());
        }

        assertTrue(names.stream().anyMatch(n -> n.contains("UnaryMethod")));
        assertTrue(names.stream().anyMatch(n -> n.contains("ClientCalls")));
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
    void interceptor_before_setsStartTime() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "invoke", null, null);
        assertDoesNotThrow(() -> interceptor.before(inv));
    }

    @Test
    void interceptor_after_completesWithoutError() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "invoke", null, null);
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.after(inv));
    }

    @Test
    void interceptor_onException_cleansUp() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "invoke", null, null);
        inv.setThrowable(new RuntimeException("gRPC error"));
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.onException(inv));
    }

    @Test
    void interceptor_fullAroundCycle_noException() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(0);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "invoke", null, new Object[0]);
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.after(inv);
        });
    }

    @Test
    void extractGrpcMethodName_returnsNullForNoArguments() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "invoke", null, null);
        assertNull(plugin.extractGrpcMethodName(inv));
    }

    @Test
    void extractGrpcMethodName_returnsNullForNonMethodDescriptorArgument() {
        plugin.init(context);
        MethodInvocation inv = new MethodInvocation(Object.class, "invoke", null, new Object[]{42});
        assertNull(plugin.extractGrpcMethodName(inv));
    }

    @Test
    void clientInterceptor_beforeAndAfter_completesWithoutError() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        // Get the client interceptor (second one)
        InterceptorDefinition def = registry.definitions.get(1);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "blockingUnaryCall", null, null);
        assertDoesNotThrow(() -> {
            interceptor.before(inv);
            interceptor.after(inv);
        });
    }

    @Test
    void clientInterceptor_onException_cleansUp() {
        plugin.init(context);
        plugin.registerInterceptors(registry);

        InterceptorDefinition def = registry.definitions.get(1);
        Interceptor interceptor = def.getInterceptor();

        MethodInvocation inv = new MethodInvocation(Object.class, "blockingUnaryCall", null, null);
        inv.setThrowable(new RuntimeException("client call failed"));
        interceptor.before(inv);
        assertDoesNotThrow(() -> interceptor.onException(inv));
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
            return "grpc";
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
