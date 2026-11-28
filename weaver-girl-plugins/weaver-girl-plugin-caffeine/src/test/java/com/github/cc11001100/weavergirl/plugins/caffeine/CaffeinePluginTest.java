package com.github.cc11001100.weavergirl.plugins.caffeine;

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

class CaffeinePluginTest {

  private CaffeinePlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new CaffeinePlugin();
  }

  @Test
  void name_returnsCaffeine() {
    assertEquals("caffeine", plugin.name());
  }

  @Test
  void init_defaultRegistersBoundedAndUnbounded() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(2, defs.size());
    for (InterceptorDefinition def : defs) {
      assertEquals(MatchType.SUPER_CLASS, def.getPointcut().getClassMatcher().getMatchType());
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
  void init_invalidThreshold_usesDefault() {
    Map<String, String> config = new HashMap<>();
    config.put("slowLoadThreshold", "slow");
    plugin.init(context(config));
    assertEquals(2, registerAndCollect().size());
  }

  @Test
  void interceptor_loadCycleAndException_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);

    MethodInvocation load =
        new MethodInvocation(String.class, "computeIfAbsent", "target", new Object[] {"key"});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(load);
          def.getInterceptor().after(load);
        });

    MethodInvocation failing = new MethodInvocation(String.class, "get", "target", null);
    failing.setThrowable(new RuntimeException("loader failed"));
    assertDoesNotThrow(() -> def.getInterceptor().onException(failing));
  }

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("caffeine", config);
  }
}
