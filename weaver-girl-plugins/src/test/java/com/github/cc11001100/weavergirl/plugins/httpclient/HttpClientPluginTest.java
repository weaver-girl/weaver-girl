package com.github.cc11001100.weavergirl.plugins.httpclient;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpClientPluginTest {

  private HttpClientPlugin plugin;
  private TestPluginContext context;
  private TestInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new HttpClientPlugin();
    context = new TestPluginContext();
    registry = new TestInterceptorRegistry();
  }

  @Test
  void name_returnsHttpclient() {
    assertEquals("httpclient", plugin.name());
  }

  @Test
  void init_defaultConfig() {
    plugin.init(context);
    assertEquals(3000, plugin.getSlowThresholdMs());
    assertTrue(plugin.isPropagateTrace());
    assertEquals("X-Trace-Id", plugin.getTraceHeaderName());
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
  void init_readsPropagateTraceConfig() {
    context.config.put("propagateTrace", "false");
    plugin.init(context);
    assertFalse(plugin.isPropagateTrace());
  }

  @Test
  void init_readsTraceHeaderNameConfig() {
    context.config.put("traceHeaderName", "X-Request-Id");
    plugin.init(context);
    assertEquals("X-Request-Id", plugin.getTraceHeaderName());
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
  void registersApacheAndOkHttpInterceptors() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    List<String> names = new ArrayList<>();
    for (InterceptorDefinition def : registry.definitions) {
      names.add(def.getName());
    }

    assertTrue(names.stream().anyMatch(n -> n.contains("CloseableHttpClient")));
    assertTrue(names.stream().anyMatch(n -> n.contains("RealCall")));
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

    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    assertDoesNotThrow(() -> interceptor.before(inv));
  }

  @Test
  void interceptor_after_completesWithoutError() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    interceptor.before(inv);
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void interceptor_onException_cleansUp() {
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
  void interceptor_fullAroundCycle_noException() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(Object.class, "execute", null, new Object[] {"http://example.com"});
    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.after(inv);
        });
  }

  @Test
  void extractUrl_returnsNullForNoArguments() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    assertNull(plugin.extractUrl(inv));
  }

  @Test
  void extractUrl_returnsNullForNonHttpRequestArgument() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, new Object[] {42});
    assertNull(plugin.extractUrl(inv));
  }

  @Test
  void extractHttpMethod_returnsNullForNoArguments() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    assertNull(plugin.extractHttpMethod(inv));
  }

  @Test
  void extractHttpMethod_returnsNullForNonHttpRequestArgument() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, new Object[] {42});
    assertNull(plugin.extractHttpMethod(inv));
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
  void injectTraceHeader_doesNotThrowForNoArguments() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    assertDoesNotThrow(() -> plugin.injectTraceHeader(inv, "trace-123"));
  }

  @Test
  void injectTraceHeader_doesNotThrowForNonHttpRequestArgument() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, new Object[] {42});
    assertDoesNotThrow(() -> plugin.injectTraceHeader(inv, "trace-123"));
  }

  @Test
  void interceptor_before_withTraceId_doesNotThrow() {
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
      return config;
    }

    @Override
    public String getPluginName() {
      return "httpclient";
    }
  }

  /** Simple InterceptorRegistry implementation for testing. */
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
