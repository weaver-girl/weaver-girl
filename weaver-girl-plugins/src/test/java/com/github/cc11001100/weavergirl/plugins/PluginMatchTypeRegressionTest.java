package com.github.cc11001100.weavergirl.plugins;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.plugins.exception.ExceptionMonitorPlugin;
import com.github.cc11001100.weavergirl.plugins.grpc.GrpcPlugin;
import com.github.cc11001100.weavergirl.plugins.httpclient.HttpClientPlugin;
import com.github.cc11001100.weavergirl.plugins.jdbc.JdbcPlugin;
import com.github.cc11001100.weavergirl.plugins.kafka.KafkaPlugin;
import com.github.cc11001100.weavergirl.plugins.logging.LoggingPlugin;
import com.github.cc11001100.weavergirl.plugins.mongo.MongoPlugin;
import com.github.cc11001100.weavergirl.plugins.redis.RedisPlugin;
import com.github.cc11001100.weavergirl.plugins.servlet.ServletPlugin;
import com.github.cc11001100.weavergirl.plugins.spring.SpringPlugin;
import com.github.cc11001100.weavergirl.plugins.timing.MethodTimingPlugin;
import com.github.cc11001100.weavergirl.plugins.trace.TraceCorrelationPlugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for P22/P23: verifies that every plugin uses the correct
 * {@link MatchType} on its ClassMatchers.
 *
 * <p>The original bug was that plugins used {@code ClassMatcher.byName()} on
 * interfaces and abstract classes, which matched ZERO real requests in production.
 * These tests ensure that never happens again.</p>
 */
class PluginMatchTypeRegressionTest {

    // ---- Shared helpers ----

    private static class TestPluginContext implements PluginContext {
        final Map<String, String> config;

        TestPluginContext() {
            this(new HashMap<String, String>());
        }

        TestPluginContext(Map<String, String> config) {
            this.config = config;
        }

        @Override
        public InterceptorRegistry getRegistry() { return null; }

        @Override
        public String getConfig(String key) { return config.get(key); }

        @Override
        public String getConfig(String key, String defaultValue) { return config.getOrDefault(key, defaultValue); }

        @Override
        public Map<String, String> getAllConfig() { return Collections.unmodifiableMap(config); }

        @Override
        public String getPluginName() { return "test"; }
    }

    private static class CollectingRegistry implements InterceptorRegistry {
        final List<InterceptorDefinition> definitions = new ArrayList<>();

        @Override
        public void register(InterceptorDefinition definition) { definitions.add(definition); }

        @Override
        public boolean unregister(String name) { return definitions.removeIf(d -> d.getName().equals(name)); }

        @Override
        public List<InterceptorDefinition> getInterceptorsForClass(String className) { return definitions; }

        @Override
        public List<InterceptorDefinition> getAllDefinitions() { return Collections.unmodifiableList(definitions); }
    }

    // ---- ServletPlugin ----

    @Test
    void servletPlugin_usesCorrectMatchType() {
        ServletPlugin plugin = new ServletPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        // HttpServlet pointcuts should use SUPER_CLASS
        long httpServletSuperClass = 0;
        long filterInterface = 0;
        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String name = d.getName();
            if (name.contains("HttpServlet")) {
                if (mt == MatchType.SUPER_CLASS) httpServletSuperClass++;
            }
            if (name.contains("Filter")) {
                if (mt == MatchType.INTERFACE) filterInterface++;
            }
        }
        assertEquals(2, httpServletSuperClass, "HttpServlet pointcuts should use SUPER_CLASS (javax+jakarta)");
        assertEquals(2, filterInterface, "Filter pointcuts should use INTERFACE (javax+jakarta)");
    }

    // ---- JdbcPlugin ----

    @Test
    void jdbcPlugin_usesCorrectMatchType() {
        JdbcPlugin plugin = new JdbcPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        // Statement, PreparedStatement, Connection are all interfaces
        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String pattern = d.getPointcut().getClassMatcher().getPattern();
            if (pattern.contains("Statement") || pattern.contains("Connection")) {
                assertEquals(MatchType.INTERFACE, mt,
                    "JDBC " + pattern + " should use INTERFACE, got " + mt);
            }
        }
    }

    // ---- RedisPlugin ----

    @Test
    void redisPlugin_usesCorrectMatchType() {
        RedisPlugin plugin = new RedisPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String pattern = d.getPointcut().getClassMatcher().getPattern();
            if (pattern.contains("Jedis")) {
                assertEquals(MatchType.EXACT_NAME, mt,
                    "Jedis should use EXACT_NAME, got " + mt);
            }
            if (pattern.contains("StatefulRedisConnection")) {
                assertEquals(MatchType.INTERFACE, mt,
                    "Lettuce StatefulRedisConnection should use INTERFACE, got " + mt);
            }
        }
    }

    // ---- KafkaPlugin ----

    @Test
    void kafkaPlugin_usesCorrectMatchType() {
        KafkaPlugin plugin = new KafkaPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String pattern = d.getPointcut().getClassMatcher().getPattern();
            if (pattern.contains("KafkaProducer") || pattern.contains("KafkaConsumer")) {
                assertEquals(MatchType.NAME_PATTERN, mt,
                    "KafkaProducer/KafkaConsumer should use NAME_PATTERN, got " + mt);
            }
        }
    }

    // ---- GrpcPlugin ----

    @Test
    void grpcPlugin_usesCorrectMatchType() {
        GrpcPlugin plugin = new GrpcPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String pattern = d.getPointcut().getClassMatcher().getPattern();
            if (pattern.contains("ServerCalls") || pattern.contains("UnaryMethod")) {
                assertEquals(MatchType.SUPER_CLASS, mt,
                    "ServerCalls$UnaryMethod should use SUPER_CLASS, got " + mt);
            }
            if (pattern.contains("ClientCalls")) {
                assertEquals(MatchType.NAME_PATTERN, mt,
                    "ClientCalls should use NAME_PATTERN, got " + mt);
            }
        }
    }

    // ---- MongoPlugin ----

    @Test
    void mongoPlugin_usesCorrectMatchType() {
        MongoPlugin plugin = new MongoPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String pattern = d.getPointcut().getClassMatcher().getPattern();
            if (pattern.contains("MongoClientImpl") || pattern.contains("MongoCollectionImpl")
                    || pattern.contains("MongoDatabaseImpl")) {
                assertEquals(MatchType.NAME_PATTERN, mt,
                    "MongoDB impl classes should use NAME_PATTERN, got " + mt);
            }
        }
    }

    // ---- HttpClientPlugin ----

    @Test
    void httpClientPlugin_usesCorrectMatchType() {
        HttpClientPlugin plugin = new HttpClientPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String pattern = d.getPointcut().getClassMatcher().getPattern();
            if (pattern.contains("CloseableHttpClient")) {
                assertEquals(MatchType.SUPER_CLASS, mt,
                    "CloseableHttpClient should use SUPER_CLASS, got " + mt);
            }
            if (pattern.contains("RealCall")) {
                assertEquals(MatchType.NAME_PATTERN, mt,
                    "OkHttp RealCall should use NAME_PATTERN, got " + mt);
            }
        }
    }

    // ---- SpringPlugin ----

    @Test
    void springPlugin_usesCorrectMatchType() {
        SpringPlugin plugin = new SpringPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        for (InterceptorDefinition d : defs) {
            MatchType mt = d.getPointcut().getClassMatcher().getMatchType();
            String pattern = d.getPointcut().getClassMatcher().getPattern();
            if (pattern.contains("Controller") || pattern.contains("RestController")
                    || pattern.contains("Service") || pattern.contains("Repository")) {
                assertEquals(MatchType.ANNOTATION, mt,
                    "Spring " + pattern + " should use ANNOTATION, got " + mt);
            }
        }
    }

    // ---- MethodTimingPlugin ----

    @Test
    void methodTimingPlugin_usesCorrectMatchType() {
        MethodTimingPlugin plugin = new MethodTimingPlugin();
        Map<String, String> config = new HashMap<String, String>();
        config.put("classPattern", "com\\.example\\..*");
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext(config));
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        assertEquals(1, defs.size(), "MethodTimingPlugin should register 1 definition when classPattern is set");
        MatchType mt = defs.get(0).getPointcut().getClassMatcher().getMatchType();
        // MethodTimingPlugin uses interceptClassPattern() which creates NAME_PATTERN
        assertEquals(MatchType.NAME_PATTERN, mt,
            "MethodTimingPlugin should use NAME_PATTERN for classPattern, got " + mt);
    }

    // ---- ExceptionMonitorPlugin ----

    @Test
    void exceptionMonitorPlugin_usesCorrectMatchType() {
        ExceptionMonitorPlugin plugin = new ExceptionMonitorPlugin();
        Map<String, String> config = new HashMap<String, String>();
        config.put("classPattern", "com\\.example\\..*");
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext(config));
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        assertEquals(1, defs.size(), "ExceptionMonitorPlugin should register 1 definition when classPattern is set");
        MatchType mt = defs.get(0).getPointcut().getClassMatcher().getMatchType();
        // ExceptionMonitorPlugin uses interceptClassPattern() which creates NAME_PATTERN
        assertEquals(MatchType.NAME_PATTERN, mt,
            "ExceptionMonitorPlugin should use NAME_PATTERN for classPattern, got " + mt);
    }

    // ---- TraceCorrelationPlugin ----

    @Test
    void traceCorrelationPlugin_usesCorrectMatchType() {
        TraceCorrelationPlugin plugin = new TraceCorrelationPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.definitions;

        assertEquals(1, defs.size(), "TraceCorrelationPlugin should register 1 definition");
        MatchType mt = defs.get(0).getPointcut().getClassMatcher().getMatchType();
        // Default entryPointPattern is ".*Servlet$|.*Controller$|.*Filter$" — a regex
        assertEquals(MatchType.NAME_PATTERN, mt,
            "TraceCorrelationPlugin should use NAME_PATTERN, got " + mt);
    }

    // ---- LoggingPlugin ----

    @Test
    void loggingPlugin_registersNoInterceptors() {
        LoggingPlugin plugin = new LoggingPlugin();
        CollectingRegistry registry = new CollectingRegistry();
        plugin.init(new TestPluginContext());
        plugin.registerInterceptors(registry);

        assertEquals(0, registry.definitions.size(),
            "LoggingPlugin is a support plugin and should not register interceptors");
    }
}
