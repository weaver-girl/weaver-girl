package com.github.cc11001100.weavergirl.plugins.jdbc;

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
import com.github.cc11001100.weavergirl.core.management.AgentApiServer;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcPluginTest {

  private JdbcPlugin plugin;
  private TestPluginContext context;
  private TestInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new JdbcPlugin();
    context = new TestPluginContext();
    registry = new TestInterceptorRegistry();
  }

  @Test
  void name_returnsJdbc() {
    assertEquals("jdbc", plugin.name());
  }

  @Test
  void init_defaultConfig() {
    plugin.init(context);
    assertEquals(1000, plugin.getSlowQueryThresholdMs());
    assertTrue(plugin.isLogSql());
    assertEquals(200, plugin.getMaxSqlLength());
    assertTrue(plugin.isEnabled());
  }

  @Test
  void init_readsSlowQueryThreshold() {
    context.config.put("slowQueryThreshold", "5000");
    plugin.init(context);
    assertEquals(5000, plugin.getSlowQueryThresholdMs());
  }

  @Test
  void init_invalidSlowQueryThreshold_usesDefault() {
    context.config.put("slowQueryThreshold", "abc");
    plugin.init(context);
    assertEquals(1000, plugin.getSlowQueryThresholdMs());
  }

  @Test
  void init_readsMaxSqlLength() {
    context.config.put("maxSqlLength", "500");
    plugin.init(context);
    assertEquals(500, plugin.getMaxSqlLength());
  }

  @Test
  void init_invalidMaxSqlLength_usesDefault() {
    context.config.put("maxSqlLength", "xyz");
    plugin.init(context);
    assertEquals(200, plugin.getMaxSqlLength());
  }

  @Test
  void init_readsLogSql() {
    context.config.put("logSql", "false");
    plugin.init(context);
    assertFalse(plugin.isLogSql());
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
  void registersStatementAndConnectionInterceptors() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    List<String> names = new ArrayList<>();
    for (InterceptorDefinition def : registry.definitions) {
      names.add(def.getName());
    }

    assertTrue(names.stream().anyMatch(n -> n.contains("java.sql.Statement")));
    assertTrue(names.stream().anyMatch(n -> n.contains("java.sql.Connection")));
    assertEquals(1, names.stream().filter(n -> n.contains("-execute")).count());
  }

  @Test
  void extractSql_returnsFirstArgumentIfString() {
    plugin.init(context);
    MethodInvocation inv =
        new MethodInvocation(Object.class, "execute", null, new Object[] {"SELECT * FROM users"});
    assertEquals("SELECT * FROM users", plugin.extractSqlForTest(inv));
  }

  @Test
  void extractSql_returnsNullIfFirstArgumentNotString() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, new Object[] {42});
    assertNull(plugin.extractSqlForTest(inv));
  }

  @Test
  void extractSql_returnsNullIfNoArguments() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    assertNull(plugin.extractSqlForTest(inv));
  }

  @Test
  void executeInterceptor_before_setsStartTime() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    // Get the Statement interceptor (first one)
    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    assertDoesNotThrow(() -> interceptor.before(inv));
  }

  @Test
  void executeInterceptor_after_completesWithoutError() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(Object.class, "execute", null, new Object[] {"SELECT 1"});
    interceptor.before(inv);
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void executeInterceptor_onException_removesStartTimeAndLogs() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, null);
    inv.setThrowable(new RuntimeException("SQL error"));
    interceptor.before(inv);
    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  @Test
  void connectionInterceptor_after_completesWithoutError() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = connectionDefinition();
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(Object.class, "prepareStatement", null, new Object[] {"SELECT 1"});
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void connectionInterceptor_onException_logs() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = connectionDefinition();
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "prepareStatement", null, null);
    inv.setThrowable(new RuntimeException("connection error"));
    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  @Test
  void executeHookEmitsSqlFindingOnlyForTaintedSql() {
    plugin.init(context);
    plugin.registerInterceptors(registry);
    Interceptor interceptor = registry.definitions.get(0).getInterceptor();
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
    String tainted = new String("SELECT secret FROM users");
    try {
      Taint.openScope();
      Taint.tag(tainted, "http.parameter:q");
      MethodInvocation hit =
          new MethodInvocation(Object.class, "execute", null, new Object[] {tainted});
      interceptor.before(hit);
      interceptor.after(hit);

      MethodInvocation miss =
          new MethodInvocation(Object.class, "execute", null, new Object[] {"SELECT 1"});
      interceptor.before(miss);
      interceptor.after(miss);

      assertEquals(1, findings.size());
      assertEquals("http.parameter:q", findings.get(0).getAttributes().get("source"));
      assertEquals(Taint.SINK_SQL, findings.get(0).getAttributes().get("sink"));
      assertEquals(tainted, findings.get(0).getAttributes().get("argument"));
      System.out.println(
          "IAST jdbc source="
              + findings.get(0).getAttributes().get("source")
              + " sink="
              + findings.get(0).getAttributes().get("sink"));
    } finally {
      Taint.closeScope();
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  @Test
  void preparedStatementExecutePublishesOneFinding() throws Exception {
    plugin.init(context);
    DefaultInterceptorRegistry realRegistry = new DefaultInterceptorRegistry();
    plugin.registerInterceptors(realRegistry);

    InvocationHandler handler =
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) {
            return null;
          }
        };
    Object prepared =
        Proxy.newProxyInstance(
            JdbcPluginTest.class.getClassLoader(),
            new Class<?>[] {java.sql.PreparedStatement.class},
            handler);
    List<InterceptorDefinition> matched =
        realRegistry.getInterceptorsForClass(prepared.getClass().getName());

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
    String tainted = new String("SELECT secret FROM accounts");
    InterceptorEventPublisher.getInstance().clearHistory();
    InterceptorEventPublisher.getInstance().addListener(listener);
    try {
      Taint.openScope();
      Taint.tag(tainted, "http.parameter:q");
      MethodInvocation inv =
          new MethodInvocation(
              prepared.getClass(), "executeQuery", prepared, new Object[] {tainted});
      List<Interceptor> executeHooks = new ArrayList<Interceptor>();
      for (InterceptorDefinition def : matched) {
        if (def.getPointcut().getMethodMatcher().matches("executeQuery")) {
          executeHooks.add(def.getInterceptor());
        }
      }
      assertEquals(1, executeHooks.size(), "Statement match must cover PreparedStatement once");
      for (Interceptor hook : executeHooks) {
        hook.before(inv);
      }
      for (Interceptor hook : executeHooks) {
        hook.after(inv);
      }
      int fired = executeHooks.size();
      assertEquals(1, fired, "Statement match must cover PreparedStatement once");
      assertEquals(1, findings.size());
      assertEquals(Taint.SINK_SQL, findings.get(0).getAttributes().get("sink"));
      assertEquals("http.parameter:q", findings.get(0).getAttributes().get("source"));
      assertEquals(tainted, findings.get(0).getAttributes().get("argument"));
      assertEquals("0", findings.get(0).getAttributes().get("start"));
      assertEquals(Integer.toString(tainted.length()), findings.get(0).getAttributes().get("end"));
      int port;
      try (ServerSocket socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      AgentApiServer api = new AgentApiServer(port);
      api.start();
      try {
        HttpURLConnection connection =
            (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/events").openConnection();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          body.append(line);
        }
        reader.close();
        String listing = body.toString();
        int occurrences = 0;
        int from = 0;
        while (true) {
          int at = listing.indexOf("\"type\":\"iast-finding\"", from);
          if (at < 0) {
            break;
          }
          occurrences++;
          from = at + 1;
        }
        assertEquals(1, occurrences, "one PreparedStatement execution is one listing entry");
        assertTrue(listing.contains("\"source\":\"http.parameter:q\""));
        assertTrue(listing.contains("\"sink\":\"sql\""));
        assertTrue(listing.contains("\"start\":\"0\""));
        assertTrue(listing.contains("\"end\":\"" + tainted.length() + "\""));
        System.out.println("IAST jdbc events-route " + listing);
      } finally {
        api.stop();
      }
      System.out.println(
          "IAST jdbc prepared source="
              + findings.get(0).getAttributes().get("source")
              + " sink="
              + findings.get(0).getAttributes().get("sink")
              + " start="
              + findings.get(0).getAttributes().get("start")
              + " end="
              + findings.get(0).getAttributes().get("end"));
    } finally {
      Taint.closeScope();
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  private InterceptorDefinition connectionDefinition() {
    for (InterceptorDefinition def : registry.definitions) {
      if (def.getName().contains("java.sql.Connection")) {
        return def;
      }
    }
    throw new AssertionError("Connection interceptor was not registered");
  }

  @Test
  void allInterceptorsHavePriority10() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    for (InterceptorDefinition def : registry.definitions) {
      assertEquals(10, def.getPriority());
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
      return Collections.unmodifiableMap(config);
    }

    @Override
    public String getPluginName() {
      return "jdbc";
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
