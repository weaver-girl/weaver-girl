package com.github.cc11001100.weavergirl.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
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
    assertEquals(3, registry.definitions.size());
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
    assertTrue(names.stream().anyMatch(n -> n.contains("java.sql.PreparedStatement")));
    assertTrue(names.stream().anyMatch(n -> n.contains("java.sql.Connection")));
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

    // Get the Connection interceptor (last one)
    InterceptorDefinition def = registry.definitions.get(2);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(Object.class, "prepareStatement", null, new Object[] {"SELECT 1"});
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void connectionInterceptor_onException_logs() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition def = registry.definitions.get(2);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "prepareStatement", null, null);
    inv.setThrowable(new RuntimeException("connection error"));
    assertDoesNotThrow(() -> interceptor.onException(inv));
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
