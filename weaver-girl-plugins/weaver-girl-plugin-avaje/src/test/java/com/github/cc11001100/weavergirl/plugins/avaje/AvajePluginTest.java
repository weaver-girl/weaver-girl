package com.github.cc11001100.weavergirl.plugins.avaje;

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

class AvajePluginTest {

  private AvajePlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new AvajePlugin();
  }

  @Test
  void name_returnsAvaje() {
    assertEquals("avaje", plugin.name());
  }

  @Test
  void init_defaultRegistersTheConnectorAdapter() {
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
    config.put("slowRequestThreshold", "slow");
    plugin.init(context(config));
    assertEquals(1, registerAndCollect().size());
  }

  @Test
  void interceptor_requestCycleAndException_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);

    MethodInvocation request = new MethodInvocation(String.class, "handle", "target", new Object[] {});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(request);
          def.getInterceptor().after(request);
        });

    MethodInvocation failing = new MethodInvocation(String.class, "handle", "target", null);
    failing.setThrowable(new RuntimeException("connector closed"));
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
        return "avaje";
      }
    };
  }
}
