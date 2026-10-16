package com.github.cc11001100.weavergirl.plugins.redisson;

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

class RedissonPluginTest {

  private RedissonPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new RedissonPlugin();
  }

  @Test
  void name_returnsRedisson() {
    assertEquals("redisson", plugin.name());
  }

  @Test
  void init_defaultRegistersLockAndCommand() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(2, defs.size());
    assertEquals(MatchType.INTERFACE, defs.get(0).getPointcut().getClassMatcher().getMatchType());
    assertEquals(MatchType.SUPER_CLASS, defs.get(1).getPointcut().getClassMatcher().getMatchType());
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
    config.put("slowLockThreshold", "slow");
    config.put("slowCommandThreshold", "nope");
    plugin.init(context(config));
    assertEquals(2, registerAndCollect().size());
  }

  @Test
  void interceptor_lockAndCommandCycles_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();

    MethodInvocation lock = new MethodInvocation(String.class, "tryLock", "target", new Object[] {});
    assertDoesNotThrow(
        () -> {
          defs.get(0).getInterceptor().before(lock);
          defs.get(0).getInterceptor().after(lock);
        });

    MethodInvocation command = new MethodInvocation(String.class, "execute", "target", null);
    command.setThrowable(new RuntimeException("connection closed"));
    assertDoesNotThrow(() -> defs.get(1).getInterceptor().onException(command));
  }

  private List<InterceptorDefinition> registerAndCollect() {
    List<InterceptorDefinition> defs = new ArrayList<>();
    plugin.registerInterceptors(registry(defs));
    return defs;
  }

  private static InterceptorRegistry registry(List<InterceptorDefinition> defs) {
    return new InterceptorRegistry() {
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
    };
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
        return "redisson";
      }
    };
  }
}
