package com.github.cc11001100.weavergirl.plugins.feign;

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

class FeignPluginTest {

  private FeignPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new FeignPlugin();
  }

  @Test
  void name_returnsFeign() {
    assertEquals("feign", plugin.name());
  }

  @Test
  void init_defaultRegistersClientAndHandler() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();
    assertEquals(2, defs.size());
    assertEquals(MatchType.INTERFACE, defs.get(0).getPointcut().getClassMatcher().getMatchType());
    assertEquals(MatchType.EXACT_NAME, defs.get(1).getPointcut().getClassMatcher().getMatchType());
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
    config.put("slowCallThreshold", "slow");
    plugin.init(context(config));
    assertEquals(2, registerAndCollect().size());
  }

  @Test
  void interceptor_callCycleAndException_doNotThrow() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();

    MethodInvocation call = new MethodInvocation(String.class, "execute", "target", new Object[] {});
    assertDoesNotThrow(
        () -> {
          defs.get(0).getInterceptor().before(call);
          defs.get(0).getInterceptor().after(call);
        });

    MethodInvocation failing =
        new MethodInvocation(String.class, "executeAndDecode", "target", null);
    failing.setThrowable(new RuntimeException("connect timed out"));
    assertDoesNotThrow(() -> defs.get(1).getInterceptor().onException(failing));
  }

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("feign", config);
  }
}
