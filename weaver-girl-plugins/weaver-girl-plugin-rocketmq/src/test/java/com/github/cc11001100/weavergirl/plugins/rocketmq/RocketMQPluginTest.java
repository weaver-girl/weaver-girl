package com.github.cc11001100.weavergirl.plugins.rocketmq;

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

class RocketMQPluginTest {

  private RocketMQPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new RocketMQPlugin();
  }

  @Test
  void name_returnsRocketmq() {
    assertEquals("rocketmq", plugin.name());
  }

  @Test
  void init_defaultRegistersProducerConsumerAndBothListeners() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(4, defs.size());

    long byName = 0;
    long byInterface = 0;
    for (InterceptorDefinition def : defs) {
      MatchType type = def.getPointcut().getClassMatcher().getMatchType();
      if (type == MatchType.EXACT_NAME) byName++;
      if (type == MatchType.INTERFACE) byInterface++;
    }
    assertEquals(2, byName);
    assertEquals(2, byInterface);
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
    config.put("slowSendThreshold", "fast");
    config.put("slowConsumeThreshold", "-1ms");
    plugin.init(context(config));
    assertEquals(4, registerAndCollect().size());
  }

  @Test
  void interceptor_sendAndConsumeCycles_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();

    MethodInvocation send = new MethodInvocation(String.class, "send", "target", new Object[] {"topic"});
    assertDoesNotThrow(
        () -> {
          defs.get(0).getInterceptor().before(send);
          defs.get(0).getInterceptor().after(send);
        });

    MethodInvocation consume =
        new MethodInvocation(String.class, "consumeMessage", "target", new Object[] {});
    consume.setThrowable(new RuntimeException("broker down"));
    assertDoesNotThrow(() -> defs.get(2).getInterceptor().onException(consume));
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
        return "rocketmq";
      }
    };
  }
}
