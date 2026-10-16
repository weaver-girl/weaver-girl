package com.github.cc11001100.weavergirl.plugins.awslambda;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AwsLambdaPluginTest {

  private AwsLambdaPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new AwsLambdaPlugin();
  }

  @Test
  void name_returnsName() {
    assertEquals("awslambda", plugin.name());
  }

  @Test
  void init_defaultRegistersTheEntryPoint() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(1, defs.size());
    assertEquals(MatchType.INTERFACE, defs.get(0).getPointcut().getClassMatcher().getMatchType());
  }

  @Test
  void init_enabledFalse_registersNothing() {
    Map<String, String> config = new HashMap<>();
    config.put("enabled", "false");
    plugin.init(context(config));
    assertTrue(registerAndCollect().isEmpty());
  }

  @Test
  void init_invalidThreshold_usesDefault() {
    Map<String, String> config = new HashMap<>();
    config.put("slowInvocationThreshold", "slow");
    plugin.init(context(config));
    assertEquals(1, registerAndCollect().size());
  }

  @Test
  void interceptor_cycleAndException_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);

    MethodInvocation call = new MethodInvocation(String.class, "handleRequest", "target", new Object[] {});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(call);
          def.getInterceptor().after(call);
        });

    MethodInvocation failing = new MethodInvocation(String.class, "handleRequest", "target", null);
    failing.setThrowable(new RuntimeException("call failed"));
    assertDoesNotThrow(() -> def.getInterceptor().onException(failing));
  }

  private List<InterceptorDefinition> registerAndCollect() {
    List<InterceptorDefinition> defs = new ArrayList<>();
    plugin.registerInterceptors(
        new InterceptorRegistry() {
          @Override
          public void register(InterceptorDefinition definition) {
            defs.add(definition);
          }

          @Override
          public boolean unregister(String name) {
            return false;
          }

          @Override
          public List<InterceptorDefinition> getInterceptorsForClass(String className) {
            return defs;
          }

          @Override
          public List<InterceptorDefinition> getAllDefinitions() {
            return defs;
          }
        });
    return defs;
  }

  private static PluginContext context(Map<String, String> config) {
    return new PluginContext() {
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
        return "awslambda";
      }
    };
  }
}
