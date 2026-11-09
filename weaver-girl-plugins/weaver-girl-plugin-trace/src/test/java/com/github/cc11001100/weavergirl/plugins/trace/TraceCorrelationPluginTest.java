package com.github.cc11001100.weavergirl.plugins.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TraceCorrelationPluginTest {

  private TraceCorrelationPlugin plugin;
  private StubRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new TraceCorrelationPlugin();
    registry = new StubRegistry();
  }

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
  }

  @Test
  void name_returnsTraceCorrelation() {
    assertEquals("trace-correlation", plugin.name());
  }

  @Test
  void registerInterceptors_enabled_registersDefinition() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);
    assertEquals(1, registry.definitions.size());
  }

  @Test
  void registerInterceptors_enabledFalse_doesNotRegister() {
    Map<String, String> config = new HashMap<>();
    config.put("enabled", "false");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertTrue(registry.definitions.isEmpty());
  }

  @Test
  void init_readsConfigCorrectly() {
    Map<String, String> config = new HashMap<>();
    config.put("headerName", "X-Request-Id");
    config.put("entryPointPattern", ".*Action$");
    config.put("mdcKey", "reqId");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    // Should register with custom entryPointPattern
    assertEquals(1, registry.definitions.size());
  }

  @Test
  void defaultEntryPointsMatchApplicationTypesNotFrameworkOrH2() {
    plugin.init(new StubContext(new HashMap<String, String>()));
    plugin.registerInterceptors(registry);

    ClassMatcher matcher = registry.definitions.get(0).getPointcut().getClassMatcher();
    assertTrue(matcher.matches("com.example.web.UserServlet"));
    assertTrue(matcher.matches("com.example.web.OrderController"));
    assertTrue(matcher.matches("com.example.web.AuthFilter"));
    assertFalse(matcher.matches("org.h2.table.TableFilter"));
    assertFalse(matcher.matches("javax.servlet.http.HttpServlet"));
    assertFalse(matcher.matches("javax.servlet.GenericServlet"));
    assertFalse(matcher.matches("javax.servlet.Servlet"));
    assertFalse(matcher.matches("javax.servlet.Filter"));
    assertFalse(matcher.matches("jakarta.servlet.http.HttpServlet"));
    assertFalse(
        matcher.matches("org.eclipse.jetty.servlet.ServletHandler$Default404Servlet"));
  }

  @Test
  void registeredDefinition_hasPriority1() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);
    assertEquals(1, registry.definitions.get(0).getPriority());
  }

  @Test
  void entryPointInterceptor_setsThreadContextTraceId() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(TraceCorrelationPluginTest.class, "testMethod", null, new Object[0]);
    interceptor.before(inv);

    String traceId = ThreadContext.get("traceId");
    assertNotNull(traceId, "traceId should be set in ThreadContext after before()");
    assertFalse(traceId.isEmpty(), "traceId should not be empty");
  }

  @Test
  void entryPointInterceptor_cleansUpAfter() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(TraceCorrelationPluginTest.class, "testMethod", null, new Object[0]);
    interceptor.before(inv);
    assertNotNull(ThreadContext.get("traceId"));

    interceptor.after(inv);
    assertNull(
        ThreadContext.get("traceId"), "traceId should be removed from ThreadContext after after()");
  }

  @Test
  void entryPointInterceptor_cleansUpOnException() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(TraceCorrelationPluginTest.class, "testMethod", null, new Object[0]);
    interceptor.before(inv);
    assertNotNull(ThreadContext.get("traceId"));

    interceptor.onException(inv);
    assertNull(
        ThreadContext.get("traceId"),
        "traceId should be removed from ThreadContext after onException()");
  }

  @Test
  void traceIdFormat_containsTimestampProcessAndCounter() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(TraceCorrelationPluginTest.class, "testMethod", null, new Object[0]);
    interceptor.before(inv);

    String traceId = ThreadContext.get("traceId");
    assertNotNull(traceId);
    // Format: timestamp-processId-counter — at least two hyphens
    long hyphenCount = traceId.chars().filter(c -> c == '-').count();
    assertTrue(
        hyphenCount >= 2,
        "traceId should contain at least 2 hyphens (timestamp-processId-counter)");
  }

  private static class StubContext implements PluginContext {
    private final Map<String, String> config;

    StubContext(Map<String, String> config) {
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
      return "trace-correlation";
    }
  }

  private static class StubRegistry implements InterceptorRegistry {
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
      return new ArrayList<>();
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
      return new ArrayList<>(definitions);
    }
  }
}
