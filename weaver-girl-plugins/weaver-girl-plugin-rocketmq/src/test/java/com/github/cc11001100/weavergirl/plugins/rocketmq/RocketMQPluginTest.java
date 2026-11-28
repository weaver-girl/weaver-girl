package com.github.cc11001100.weavergirl.plugins.rocketmq;

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

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("rocketmq", config);
  }
}
