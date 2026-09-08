package com.github.cc11001100.weavergirl.plugins.logging;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LoggingPluginTest {

  private LoggingPlugin plugin;
  private TestPluginContext context;

  @BeforeEach
  void setUp() {
    plugin = new LoggingPlugin();
    context = new TestPluginContext();
    ThreadContext.clear();
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
    MDC.clear();
  }

  @Test
  void name_returnsLogging() {
    assertEquals("logging", plugin.name());
  }

  @Test
  void init_defaultConfig() {
    plugin.init(context);
    assertArrayEquals(new String[] {"traceId", "spanId", "method"}, plugin.getMdcKeys());
    assertTrue(plugin.isEnabled());
  }

  @Test
  void init_readsMdcKeysConfig() {
    context.config.put("mdcKeys", "traceId,spanId,method,appId");
    plugin.init(context);
    assertArrayEquals(new String[] {"traceId", "spanId", "method", "appId"}, plugin.getMdcKeys());
  }

  @Test
  void init_readsMdcKeysWithSpaces() {
    context.config.put("mdcKeys", "traceId, spanId , method");
    plugin.init(context);
    assertArrayEquals(new String[] {"traceId", "spanId", "method"}, plugin.getMdcKeys());
  }

  @Test
  void init_readsEnabledConfig() {
    context.config.put("enabled", "false");
    plugin.init(context);
    assertFalse(plugin.isEnabled());
  }

  @Test
  void init_enabledTrue() {
    context.config.put("enabled", "true");
    plugin.init(context);
    assertTrue(plugin.isEnabled());
  }

  @Test
  void depends_returnsTraceCorrelation() {
    String[] deps = plugin.depends();
    assertArrayEquals(new String[] {"trace-correlation"}, deps);
  }

  @Test
  void registerInterceptors_registersNothing() {
    plugin.init(context);
    TestInterceptorRegistry registry = new TestInterceptorRegistry();
    plugin.registerInterceptors(registry);
    assertEquals(0, registry.definitions.size());
  }

  @Test
  void injectContext_setsTraceIdAndMethodInMDC() {
    ThreadContext.put("traceId", "abc-123");
    LoggingPlugin.injectContext("MyService", "handleRequest");

    assertEquals("abc-123", MDC.get("traceId"));
    assertEquals("MyService.handleRequest", MDC.get("method"));
  }

  @Test
  void injectContext_setsSpanIdInMDC() {
    ThreadContext.put("traceId", "trace-1");
    ThreadContext.put("spanId", "span-1");
    LoggingPlugin.injectContext("OrderService", "process");

    assertEquals("trace-1", MDC.get("traceId"));
    assertEquals("span-1", MDC.get("spanId"));
    assertEquals("OrderService.process", MDC.get("method"));
  }

  @Test
  void injectContext_noTraceId_doesNotSetTraceIdMDC() {
    LoggingPlugin.injectContext("MyService", "handleRequest");
    assertNull(MDC.get("traceId"));
    assertEquals("MyService.handleRequest", MDC.get("method"));
  }

  @Test
  void clearContext_removesAllMDCEntries() {
    ThreadContext.put("traceId", "t1");
    ThreadContext.put("spanId", "s1");
    LoggingPlugin.injectContext("Svc", "doWork");

    assertNotNull(MDC.get("traceId"));
    assertNotNull(MDC.get("spanId"));
    assertNotNull(MDC.get("method"));

    LoggingPlugin.clearContext();

    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("spanId"));
    assertNull(MDC.get("method"));
  }

  @Test
  void injectContext_clearContext_roundTrip() {
    ThreadContext.put("traceId", "round-trip-trace");
    LoggingPlugin.injectContext("Svc", "run");
    assertEquals("round-trip-trace", MDC.get("traceId"));
    LoggingPlugin.clearContext();
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("method"));
  }

  /** Simple PluginContext implementation for testing. */
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
      return "logging";
    }
  }

  /** Simple InterceptorRegistry implementation for testing. */
  private static class TestInterceptorRegistry implements InterceptorRegistry {
    final List<com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition> definitions =
        new ArrayList<>();

    @Override
    public void register(
        com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition definition) {
      definitions.add(definition);
    }

    @Override
    public boolean unregister(String name) {
      return definitions.removeIf(d -> d.getName().equals(name));
    }

    @Override
    public List<com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition>
        getInterceptorsForClass(String className) {
      List<com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition> result =
          new ArrayList<>();
      for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
          definitions) {
        if (def.getPointcut().getClassMatcher().matches(className)) {
          result.add(def);
        }
      }
      return result;
    }

    @Override
    public List<com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition>
        getAllDefinitions() {
      return Collections.unmodifiableList(definitions);
    }
  }
}
