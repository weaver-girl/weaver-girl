package com.github.cc11001100.weavergirl.plugins.mybatis;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyBatisPluginTest {

  private MyBatisPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new MyBatisPlugin();
  }

  @Test
  void name_returnsMybatis() {
    assertEquals("mybatis", plugin.name());
  }

  @Test
  void init_defaultRegistersExecutorAndStatementHandler() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(2, defs.size());
    for (InterceptorDefinition def : defs) {
      assertEquals(MatchType.INTERFACE, def.getPointcut().getClassMatcher().getMatchType());
    }
  }

  @Test
  void init_enabledFalse_registersNothing() {
    Map<String, String> config = new HashMap<>();
    config.put("enabled", "false");
    plugin.init(context(config));
    assertTrue(registerAndCollect().isEmpty());
  }

  @Test
  void init_logStatementIdFalse_stillRegisters() {
    Map<String, String> config = new HashMap<>();
    config.put("logStatementId", "false");
    plugin.init(context(config));
    assertFalse(registerAndCollect().isEmpty());
  }

  @Test
  void init_invalidThreshold_usesDefault() {
    Map<String, String> config = new HashMap<>();
    config.put("slowQueryThreshold", "slow");
    plugin.init(context(config));
    assertEquals(2, registerAndCollect().size());
  }

  @Test
  void interceptor_statementIdAndMissingArgs_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);

    MethodInvocation withId =
        new MethodInvocation(
            String.class, "query", "target", new Object[] {"com.example.UserMapper.findById"});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(withId);
          def.getInterceptor().after(withId);
        });

    MethodInvocation noArgs = new MethodInvocation(String.class, "update", "target", null);
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(noArgs);
          def.getInterceptor().after(noArgs);
        });

    MethodInvocation failing = new MethodInvocation(String.class, "query", "target", new Object[] {7});
    failing.setThrowable(new RuntimeException("bad sql"));
    assertDoesNotThrow(() -> def.getInterceptor().onException(failing));

    // Executor passes a MappedStatement, whose id is read through getId().
    MethodInvocation mapped =
        new MethodInvocation(String.class, "query", "target", new Object[] {new MappedStatementStub()});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(mapped);
          def.getInterceptor().after(mapped);
        });
  }

  /** Stands in for {@code org.apache.ibatis.mapping.MappedStatement}, which is not on the classpath. */
  public static class MappedStatementStub {
    public String getId() {
      return "com.example.UserMapper.findById";
    }
  }

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("mybatis", config);
  }
}
