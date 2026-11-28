package com.github.cc11001100.weavergirl.plugins.ghttp;

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

class GHttpPluginTest {

  private GHttpPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new GHttpPlugin();
  }

  @Test
  void name_returnsGHttp() {
    assertEquals("ghttp", plugin.name());
  }

  @Test
  void init_defaultRegistersTheConnectorAdapter() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(1, defs.size());
    assertEquals(MatchType.EXACT_NAME, defs.get(0).getPointcut().getClassMatcher().getMatchType());
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

    MethodInvocation request = new MethodInvocation(String.class, "execute", "target", new Object[] {});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(request);
          def.getInterceptor().after(request);
        });

    MethodInvocation failing = new MethodInvocation(String.class, "execute", "target", null);
    failing.setThrowable(new RuntimeException("connector closed"));
    assertDoesNotThrow(() -> def.getInterceptor().onException(failing));
  }

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("ghttp", config);
  }
}
