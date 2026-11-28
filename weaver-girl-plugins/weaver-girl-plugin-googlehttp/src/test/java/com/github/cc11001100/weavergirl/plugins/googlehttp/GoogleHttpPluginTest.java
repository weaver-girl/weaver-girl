package com.github.cc11001100.weavergirl.plugins.googlehttp;

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

class GoogleHttpPluginTest {

  private GoogleHttpPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new GoogleHttpPlugin();
  }

  @Test
  void name_returnsName() {
    assertEquals("googlehttp", plugin.name());
  }

  @Test
  void init_defaultRegistersTheEntryPoint() {
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
  void interceptor_cycleAndException_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);

    MethodInvocation call = new MethodInvocation(String.class, "execute", "target", new Object[] {});
    assertDoesNotThrow(
        () -> {
          def.getInterceptor().before(call);
          def.getInterceptor().after(call);
        });

    MethodInvocation failing = new MethodInvocation(String.class, "execute", "target", null);
    failing.setThrowable(new RuntimeException("call failed"));
    assertDoesNotThrow(() -> def.getInterceptor().onException(failing));
  }

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("googlehttp", config);
  }
}
