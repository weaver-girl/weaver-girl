package com.github.cc11001100.weavergirl.plugins.druid;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.testkit.RecordingInterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DruidPluginTest {

  private DruidPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new DruidPlugin();
  }

  @Test
  void name_returnsDruid() {
    assertEquals("druid", plugin.name());
  }

  @Test
  void init_defaultRegistersStatementFilterConnectionAndDataSource() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(4, defs.size());

    long byName = 0;
    long bySuperClass = 0;
    long byInterface = 0;
    for (InterceptorDefinition def : defs) {
      MatchType type = def.getPointcut().getClassMatcher().getMatchType();
      if (type == MatchType.EXACT_NAME) byName++;
      if (type == MatchType.SUPER_CLASS) bySuperClass++;
      if (type == MatchType.INTERFACE) byInterface++;
    }
    assertEquals(2, byName);
    assertEquals(1, bySuperClass);
    assertEquals(1, byInterface);
  }

  @Test
  void init_enabledFalse_registersNothing() {
    Map<String, String> config = new HashMap<>();
    config.put("enabled", "false");
    plugin.init(context(config));
    assertTrue(registerAndCollect().isEmpty());
  }

  @Test
  void init_invalidThresholds_useDefaults() {
    Map<String, String> config = new HashMap<>();
    config.put("slowSqlThreshold", "nope");
    config.put("slowConnectionThreshold", "also-nope");
    plugin.init(context(config));
    assertEquals(4, registerAndCollect().size());
  }

  @Test
  void interceptor_sqlAndConnectionCycles_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();

    MethodInvocation sql =
        new MethodInvocation(String.class, "executeQuery", "target", new Object[] {"select 1"});
    assertDoesNotThrow(
        () -> {
          defs.get(0).getInterceptor().before(sql);
          defs.get(0).getInterceptor().after(sql);
        });

    MethodInvocation connection = new MethodInvocation(String.class, "getConnection", "target", null);
    connection.setThrowable(new RuntimeException("pool exhausted"));
    assertDoesNotThrow(() -> defs.get(3).getInterceptor().onException(connection));
  }

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("druid", config);
  }
}
