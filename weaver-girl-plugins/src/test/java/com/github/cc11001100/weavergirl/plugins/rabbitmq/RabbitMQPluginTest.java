package com.github.cc11001100.weavergirl.plugins.rabbitmq;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RabbitMQPluginTest {

  private RabbitMQPlugin plugin;
  private TestPluginContext context;
  private TestInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new RabbitMQPlugin();
    context = new TestPluginContext();
    registry = new TestInterceptorRegistry();
  }

  @Test
  void name_returnsRabbitmq() {
    assertEquals("rabbitmq", plugin.name());
  }

  @Test
  void init_defaultConfig() {
    plugin.init(context);
    assertEquals(3000, plugin.getSlowPublishThresholdMs());
    assertEquals(5000, plugin.getSlowConsumeThresholdMs());
    assertTrue(plugin.isTrackMessageSize());
    assertTrue(plugin.isPropagateTrace());
    assertTrue(plugin.isEnabled());
  }

  @Test
  void init_readsSlowPublishThresholdConfig() {
    context.config.put("slowPublishThreshold", "10000");
    plugin.init(context);
    assertEquals(10000, plugin.getSlowPublishThresholdMs());
  }

  @Test
  void init_readsSlowConsumeThresholdConfig() {
    context.config.put("slowConsumeThreshold", "8000");
    plugin.init(context);
    assertEquals(8000, plugin.getSlowConsumeThresholdMs());
  }

  @Test
  void init_invalidSlowPublishThreshold_usesDefault() {
    context.config.put("slowPublishThreshold", "not-a-number");
    plugin.init(context);
    assertEquals(3000, plugin.getSlowPublishThresholdMs());
  }

  @Test
  void init_invalidSlowConsumeThreshold_usesDefault() {
    context.config.put("slowConsumeThreshold", "not-a-number");
    plugin.init(context);
    assertEquals(5000, plugin.getSlowConsumeThresholdMs());
  }

  @Test
  void init_readsTrackMessageSizeConfig() {
    context.config.put("trackMessageSize", "false");
    plugin.init(context);
    assertFalse(plugin.isTrackMessageSize());
  }

  @Test
  void init_readsPropagateTraceConfig() {
    context.config.put("propagateTrace", "false");
    plugin.init(context);
    assertFalse(plugin.isPropagateTrace());
  }

  @Test
  void enabledFalse_registersNoInterceptors() {
    context.config.put("enabled", "false");
    plugin.init(context);
    plugin.registerInterceptors(registry);
    assertEquals(0, registry.definitions.size());
  }

  @Test
  void enabledTrue_registersThreeInterceptors() {
    plugin.init(context);
    plugin.registerInterceptors(registry);
    // basicPublish + basicAck/Nack/Reject + newConnection = 3
    assertEquals(3, registry.definitions.size());
  }

  @Test
  void registersPublishAckAndConnectionInterceptors() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    List<String> methods = new ArrayList<>();
    for (InterceptorDefinition def : registry.definitions) {
      methods.add(def.getName());
    }

    // Verify each interceptor targets correct methods
    assertTrue(
        methods.stream().anyMatch(n -> n.contains("basicPublish")),
        "Should have basicPublish interceptor, got: " + methods);
    assertTrue(
        methods.stream().anyMatch(n -> n.contains("basicAck")),
        "Should have basicAck interceptor, got: " + methods);
    assertTrue(
        methods.stream().anyMatch(n -> n.contains("newConnection")),
        "Should have newConnection interceptor, got: " + methods);
  }

  @Test
  void allInterceptorsHavePriority10() {
    plugin.init(context);
    plugin.registerInterceptors(registry);
    for (InterceptorDefinition def : registry.definitions) {
      assertEquals(10, def.getPriority());
    }
  }

  @Test
  void publishInterceptor_before_after_noException() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition pubDef =
        registry.definitions.stream()
            .filter(d -> d.getName().contains("basicPublish"))
            .findFirst()
            .orElseThrow();
    Interceptor interceptor = pubDef.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(
            Object.class,
            "basicPublish",
            null,
            new Object[] {"my-exchange", "my.routing.key", null, null, null, "body".getBytes()});
    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.after(inv);
        });
  }

  @Test
  void publishInterceptor_onException_cleansUp() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition pubDef =
        registry.definitions.stream()
            .filter(d -> d.getName().contains("basicPublish"))
            .findFirst()
            .orElseThrow();
    Interceptor interceptor = pubDef.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(Object.class, "basicPublish", null, new Object[] {"exchange", "key"});
    inv.setThrowable(new RuntimeException("channel closed"));
    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.onException(inv);
        });
  }

  @Test
  void ackInterceptor_before_after_noException() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition ackDef =
        registry.definitions.stream()
            .filter(d -> d.getName().contains("basicAck"))
            .findFirst()
            .orElseThrow();
    Interceptor interceptor = ackDef.getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(Object.class, "basicAck", null, new Object[] {123L, false});
    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.after(inv);
        });
  }

  @Test
  void connectionInterceptor_before_after_noException() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition connDef =
        registry.definitions.stream()
            .filter(d -> d.getName().contains("newConnection"))
            .findFirst()
            .orElseThrow();
    Interceptor interceptor = connDef.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "newConnection", null, null);
    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.after(inv);
        });
  }

  @Test
  void connectionInterceptor_onException_cleansUp() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition connDef =
        registry.definitions.stream()
            .filter(d -> d.getName().contains("newConnection"))
            .findFirst()
            .orElseThrow();
    Interceptor interceptor = connDef.getInterceptor();

    MethodInvocation inv = new MethodInvocation(Object.class, "newConnection", null, null);
    inv.setThrowable(new RuntimeException("connection refused"));
    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.onException(inv);
        });
  }

  @Test
  void extractStringArg_returnsValue() {
    plugin.init(context);
    MethodInvocation inv =
        new MethodInvocation(
            Object.class, "publish", null, new Object[] {"exchange", "routingKey"});
    assertEquals("exchange", plugin.extractStringArg(inv, 0));
    assertEquals("routingKey", plugin.extractStringArg(inv, 1));
  }

  @Test
  void extractStringArg_returnsNullForOutOfBounds() {
    plugin.init(context);
    MethodInvocation inv =
        new MethodInvocation(Object.class, "publish", null, new Object[] {"only-one"});
    assertNull(plugin.extractStringArg(inv, 5));
  }

  @Test
  void extractStringArg_returnsNullForNoArgs() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "publish", null, null);
    assertNull(plugin.extractStringArg(inv, 0));
  }

  @Test
  void extractLongArg_returnsValue() {
    plugin.init(context);
    MethodInvocation inv =
        new MethodInvocation(Object.class, "ack", null, new Object[] {42L, false});
    assertEquals(42L, plugin.extractLongArg(inv, 0));
  }

  @Test
  void extractLongArg_returnsNegativeForNoArgs() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "ack", null, null);
    assertEquals(-1, plugin.extractLongArg(inv, 0));
  }

  @Test
  void extractMessageSize_returnsSize() {
    plugin.init(context);
    byte[] body = "hello world".getBytes();
    MethodInvocation inv =
        new MethodInvocation(
            Object.class, "publish", null, new Object[] {"ex", "key", null, null, null, body});
    assertEquals(body.length, plugin.extractMessageSize(inv));
  }

  @Test
  void extractMessageSize_returnsNegativeForNoBody() {
    plugin.init(context);
    MethodInvocation inv =
        new MethodInvocation(Object.class, "publish", null, new Object[] {"ex", "key"});
    assertEquals(-1, plugin.extractMessageSize(inv));
  }

  @Test
  void extractHostFromFactory_returnsNullForNoTarget() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "newConnection", null, null);
    assertNull(plugin.extractHostFromFactory(inv));
  }

  @Test
  void extractPortFromFactory_returnsNegativeForNoTarget() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "newConnection", null, null);
    assertEquals(-1, plugin.extractPortFromFactory(inv));
  }

  @Test
  void injectTraceIntoProperties_doesNotThrowForNoArgs() {
    plugin.init(context);
    MethodInvocation inv = new MethodInvocation(Object.class, "publish", null, null);
    assertDoesNotThrow(() -> plugin.injectTraceIntoProperties(inv));
  }

  @Test
  void publishInterceptor_withTraceId_doesNotThrow() {
    plugin.init(context);
    plugin.registerInterceptors(registry);

    InterceptorDefinition pubDef =
        registry.definitions.stream()
            .filter(d -> d.getName().contains("basicPublish"))
            .findFirst()
            .orElseThrow();
    Interceptor interceptor = pubDef.getInterceptor();

    com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", "trace-123");
    try {
      MethodInvocation inv =
          new MethodInvocation(
              Object.class, "basicPublish", null, new Object[] {"exchange", "key"});
      assertDoesNotThrow(() -> interceptor.before(inv));
    } finally {
      com.github.cc11001100.weavergirl.api.context.ThreadContext.clear();
    }
  }

  /** Simple PluginContext implementation for testing. */
  private static class TestPluginContext implements PluginContext {
    final Map<String, String> config = new HashMap<>();

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
      return "rabbitmq";
    }
  }

  /** Simple InterceptorRegistry implementation for testing. */
  private static class TestInterceptorRegistry implements InterceptorRegistry {
    final List<InterceptorDefinition> definitions = new ArrayList<>();

    @Override
    public void register(InterceptorDefinition definition) {
      definitions.add(definition);
    }

    @Override
    public boolean unregister(String name) {
      return definitions.removeIf(d -> d.getName().equals(name));
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
      List<InterceptorDefinition> result = new ArrayList<>();
      for (InterceptorDefinition def : definitions) {
        if (def.getPointcut().getClassMatcher().matches(className)) result.add(def);
      }
      return result;
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
      return definitions;
    }
  }
}
