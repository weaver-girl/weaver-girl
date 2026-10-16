package com.github.cc11001100.weavergirl.plugins.dubbo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class DubboPluginTest {

  private DubboPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new DubboPlugin();
  }

  @Test
  void name_returnsDubbo() {
    assertEquals("dubbo", plugin.name());
  }

  @Test
  void init_defaultRegistersThreeInterceptors() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(3, defs.size());
  }

  @Test
  void init_enabledFalse_registersNothing() {
    Map<String, String> config = new HashMap<>();
    config.put("enabled", "false");
    plugin.init(context(config));
    assertTrue(registerAndCollect().isEmpty());
  }

  @Test
  void init_invalidThreshold_usesDefaultAndStillRegisters() {
    Map<String, String> config = new HashMap<>();
    config.put("slowInvocationThreshold", "not-a-number");
    plugin.init(context(config));
    assertFalse(registerAndCollect().isEmpty());
  }

  @Test
  void matchTypes_interfacesByInterface_proxyInvokerBySuperClass() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();

    long byInterface = 0;
    long bySuperClass = 0;
    for (InterceptorDefinition def : defs) {
      MatchType type = def.getPointcut().getClassMatcher().getMatchType();
      if (type == MatchType.INTERFACE) byInterface++;
      if (type == MatchType.SUPER_CLASS) bySuperClass++;
    }
    assertEquals(2, byInterface);
    assertEquals(1, bySuperClass);
  }

  @Test
  void interceptor_aroundCycleAndException_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);
    MethodInvocation inv = new MethodInvocation(String.class, "invoke", "target", new Object[] {});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(inv);
          def.getInterceptor().after(inv);
        });

    MethodInvocation failing = new MethodInvocation(String.class, "invoke", "target", null);
    failing.setThrowable(new RuntimeException("rpc timeout"));
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
        return "dubbo";
      }
    };
  }
}
