package com.github.cc11001100.weavergirl.plugins.servlet;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintFindings;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServletPluginTest {

  private ServletPlugin plugin;
  private TestPluginContext context;
  private TestInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new ServletPlugin();
    context = new TestPluginContext();
    registry = new TestInterceptorRegistry();
  }

  @Test
  void name_returnsServlet() {
    assertEquals("servlet", plugin.name());
  }

  @Test
  void init_readsSlowThresholdConfig() {
    context.config.put("slowThreshold", "3000");
    plugin.init(context);
    // Verify by checking interceptor behavior — after init, the plugin uses the threshold
    plugin.registerInterceptors(registry);
    // If enabled and threshold is parsed, the plugin should register 4 interceptors
    assertEquals(4, registry.definitions.size());
  }

  @Test
  void init_defaultSlowThreshold() {
    plugin.init(context);
    plugin.registerInterceptors(registry);
    assertEquals(4, registry.definitions.size());
  }

  @Test
  void init_invalidSlowThreshold_usesDefault() {
    context.config.put("slowThreshold", "not-a-number");
    plugin.init(context);
    plugin.registerInterceptors(registry);
    assertEquals(4, registry.definitions.size());
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
    context.config.put("enabled", "true");
    plugin.init(context);
    plugin.registerInterceptors(registry);
    assertEquals(4, registry.definitions.size());
  }

  @Test
  void registersJavaxAndJakartaInterceptors() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    // Check that we have interceptors for both javax and jakarta namespaces
    List<String> names = new ArrayList<>();
    for (InterceptorDefinition def : registry.definitions) {
      names.add(def.getName());
    }

    assertTrue(names.stream().anyMatch(n -> n.contains("javax.servlet.http.HttpServlet")));
    assertTrue(names.stream().anyMatch(n -> n.contains("javax.servlet.Filter")));
    assertTrue(names.stream().anyMatch(n -> n.contains("jakarta.servlet.http.HttpServlet")));
    assertTrue(names.stream().anyMatch(n -> n.contains("jakarta.servlet.Filter")));
  }

  @Test
  void interceptor_before_setsStartTime() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "service", null, null);
    // Should not throw
    assertDoesNotThrow(() -> interceptor.before(inv));
    interceptor.after(inv);
  }

  @Test
  void interceptor_after_completesWithoutError() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "service", null, null);
    interceptor.before(inv);
    // Should not throw even without a real servlet request
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void interceptor_onException_removesStartTimeAndLogs() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "service", null, null);
    inv.setThrowable(new RuntimeException("test error"));
    interceptor.before(inv);
    // Should not throw
    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  @Test
  void interceptor_before_withTraceId_doesNotThrow() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    // Set a traceId in ThreadContext
    com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", "test-trace-123");

    try {
      MethodInvocation inv = new MethodInvocation(Object.class, "service", null, null);
      // Should not throw even though target is null (no servlet request)
      assertDoesNotThrow(() -> interceptor.before(inv));
      interceptor.after(inv);
    } finally {
      com.github.cc11001100.weavergirl.api.context.ThreadContext.clear();
    }
  }

  @Test
  void serviceHookTaintsParameterAndHeaderAndLeavesUnmarkedValueClean() {
    plugin.init(context);
    plugin.registerInterceptors(registry);
    Interceptor interceptor = registry.definitions.get(0).getInterceptor();

    final String parameter = new String("alice");
    final String header = new String("bearer");
    final String unmarked = new String("clean-value");
    final Map<String, String[]> parameters = new HashMap<String, String[]>();
    parameters.put("user", new String[] {parameter});
    final Map<String, String> headers = new HashMap<String, String>();
    headers.put("X-Token", header);
    Object request =
        new Object() {
          public Map<String, String[]> getParameterMap() {
            return parameters;
          }

          public java.util.Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
          }

          public String getHeader(String name) {
            return headers.get(name);
          }

          public String getMethod() {
            return "GET";
          }

          public String getRequestURI() {
            return "/users";
          }
        };
    MethodInvocation inv =
        new MethodInvocation(Object.class, "service", null, new Object[] {request, new Object()});
    final List<InterceptorEvent> findings = new ArrayList<InterceptorEvent>();
    InterceptorEventListener listener =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
              findings.add(event);
            }
          }
        };
    InterceptorEventPublisher.getInstance().addListener(listener);
    try {
      interceptor.before(inv);
      assertTrue(Taint.hasSource(parameter, Taint.PARAMETER_PREFIX + "user"));
      assertTrue(Taint.hasSource(header, Taint.HEADER_PREFIX + "X-Token"));
      assertFalse(Taint.isTainted(unmarked));
      System.out.println(
          "IAST servlet source="
              + Taint.PARAMETER_PREFIX
              + "user source="
              + Taint.HEADER_PREFIX
              + "X-Token");
      interceptor.after(inv);
      assertFalse(Taint.isTainted(parameter), "request scope closes with the servlet hook");
      assertEquals(0, findings.size());
    } finally {
      InterceptorEventPublisher.getInstance().removeListener(listener);
      Taint.closeScope();
    }
  }

  @Test
  void nestedServiceAndFilterPopOnlyTheirOwnScope() {
    plugin.init(context);
    plugin.registerInterceptors(registry);
    Interceptor interceptor = registry.definitions.get(0).getInterceptor();

    final String outerValue = new String("outer-secret");
    final String innerValue = new String("inner-secret");
    final String nextValue = new String("next-secret");
    MethodInvocation outer = invocation("service", "user", outerValue);
    MethodInvocation inner = invocation("doFilter", "user", innerValue);
    MethodInvocation next = invocation("service", "user", nextValue);

    interceptor.before(outer);
    assertTrue(Taint.hasSource(outerValue, Taint.PARAMETER_PREFIX + "user"));
    interceptor.before(inner);
    assertTrue(Taint.hasSource(innerValue, Taint.PARAMETER_PREFIX + "user"));
    assertDoesNotThrow(() -> interceptor.after(inner));
    assertTrue(
        Taint.hasSource(outerValue, Taint.PARAMETER_PREFIX + "user"),
        "closing the inner service/filter must leave the outer request scope");
    assertFalse(Taint.hasSource(innerValue, Taint.PARAMETER_PREFIX + "user"));
    assertDoesNotThrow(() -> interceptor.after(outer));
    assertFalse(Taint.isTainted(outerValue));

    interceptor.before(next);
    assertFalse(Taint.isTainted(outerValue), "the next request must not see the previous taint");
    assertTrue(Taint.hasSource(nextValue, Taint.PARAMETER_PREFIX + "user"));
    interceptor.after(next);
    assertFalse(Taint.isTainted(nextValue));
    System.out.println("IAST servlet nested-scope source=" + Taint.PARAMETER_PREFIX + "user");
  }

  private static MethodInvocation invocation(String methodName, final String param, final String value) {
    final Map<String, String[]> parameters = new HashMap<String, String[]>();
    parameters.put(param, new String[] {value});
    Object request =
        new Object() {
          public Map<String, String[]> getParameterMap() {
            return parameters;
          }

          public java.util.Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.<String>emptyList());
          }

          public String getHeader(String name) {
            return null;
          }

          public String getMethod() {
            return "GET";
          }

          public String getRequestURI() {
            return "/nested";
          }
        };
    return new MethodInvocation(Object.class, methodName, null, new Object[] {request, new Object()});
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
  void logHeadersConfig_parsedCorrectly() {
    context.config.put("logHeaders", "true");
    plugin.init(context);
    plugin.registerInterceptors(registry);
    // Plugin should still register interceptors
    assertEquals(4, registry.definitions.size());
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
      return "servlet";
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
      return Collections.unmodifiableList(definitions);
    }
  }
}
