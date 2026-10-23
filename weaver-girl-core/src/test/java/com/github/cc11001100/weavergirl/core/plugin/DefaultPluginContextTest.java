package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for DefaultPluginContext.
 */
class DefaultPluginContextTest {

  private InterceptorRegistry mockRegistry;

  @BeforeEach
  void setUp() {
    mockRegistry = Mockito.mock(InterceptorRegistry.class);
  }

  @Test
  void constructor_withValidParameters() {
    Map<String, String> config = new HashMap<>();
    config.put("key1", "value1");

    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    assertEquals(mockRegistry, context.getRegistry());
    assertEquals("test-plugin", context.getPluginName());
  }

  @Test
  void constructor_withNullConfig_usesEmptyMap() {
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, null, "test-plugin");

    assertNotNull(context.getAllConfig());
    assertTrue(context.getAllConfig().isEmpty());
  }

  @Test
  void getConfig_fromSystemProperty_namespaced() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    System.setProperty("weavergirl.plugin.myplugin.dbUrl", "jdbc:test");

    try {
      assertEquals("jdbc:test", context.getConfig("dbUrl"));
    } finally {
      System.clearProperty("weavergirl.plugin.myplugin.dbUrl");
    }
  }

  @Test
  void getConfig_fromSystemProperty_plain() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    System.setProperty("plainKey", "plainValue");

    try {
      assertEquals("plainValue", context.getConfig("plainKey"));
    } finally {
      System.clearProperty("plainKey");
    }
  }

  @Test
  void getConfig_fromConfigMap() {
    Map<String, String> config = new HashMap<>();
    config.put("mapKey", "mapValue");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    assertEquals("mapValue", context.getConfig("mapKey"));
  }

  @Test
  void getConfig_precedence_systemPropertyOverConfigMap() {
    Map<String, String> config = new HashMap<>();
    config.put("key", "fromMap");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    System.setProperty("key", "fromSystem");

    try {
      assertEquals("fromSystem", context.getConfig("key"));
    } finally {
      System.clearProperty("key");
    }
  }

  @Test
  void getConfig_withDefaultValue_returnsDefaultWhenNotFound() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    assertEquals("defaultVal", context.getConfig("nonexistent", "defaultVal"));
  }

  @Test
  void getConfig_withDefaultValue_stillReadsSystemProperties() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");
    System.setProperty("weavergirl.plugin.myplugin.threshold", "250");

    try {
      assertEquals("250", context.getConfig("threshold", "1000"));
    } finally {
      System.clearProperty("weavergirl.plugin.myplugin.threshold");
    }
  }

  @Test
  void getConfig_withDefaultValue_readsPluginSection() {
    Map<String, String> config = new HashMap<>();
    config.put("plugins.myplugin.threshold", "250");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    assertEquals("250", context.getConfig("threshold", "1000"));
  }

  @Test
  void getConfig_pluginSectionWinsOverAnotherPluginsGlobalKey() {
    Map<String, String> config = new HashMap<>();
    config.put("threshold", "1000");
    config.put("plugins.other.threshold", "999");
    config.put("plugins.myplugin.threshold", "250");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    assertEquals("250", context.getConfig("threshold"));
    // A different plugin is not affected by this plugin's scoped entry.
    DefaultPluginContext other = new DefaultPluginContext(mockRegistry, config, "other");
    assertEquals("999", other.getConfig("threshold"));
  }

  @Test
  void getConfig_namespacedPropertyWinsOverPluginSection() {
    Map<String, String> config = new HashMap<>();
    config.put("plugins.myplugin.threshold", "250");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");
    System.setProperty("weavergirl.plugin.myplugin.threshold", "42");

    try {
      assertEquals("42", context.getConfig("threshold"));
    } finally {
      System.clearProperty("weavergirl.plugin.myplugin.threshold");
    }
  }

  @Test
  void getAllConfig_returnsUnmodifiableMap() {
    Map<String, String> config = new HashMap<>();
    config.put("key", "value");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    Map<String, String> allConfig = context.getAllConfig();
    assertEquals("value", allConfig.get("key"));
    assertThrows(UnsupportedOperationException.class, () -> allConfig.put("new", "val"));
  }

  @Test
  void getPluginName_returnsCorrectName() {
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, null, "my-awesome-plugin");

    assertEquals("my-awesome-plugin", context.getPluginName());
  }

  @Test
  void withdrawServices_detachesObservabilityContributions() {
    com.github.cc11001100.weavergirl.api.observability.SpanSink sink =
        new com.github.cc11001100.weavergirl.api.observability.SpanSink() {
          @Override
          public String name() {
            return "test-sink";
          }

          @Override
          public void onSpanEnd(
              com.github.cc11001100.weavergirl.api.observability.SpanRecord span) {}
        };
    com.github.cc11001100.weavergirl.api.observability.MetricSource source =
        new com.github.cc11001100.weavergirl.api.observability.MetricSource() {
          @Override
          public String name() {
            return "test-source";
          }

          @Override
          public void collect(
              com.github.cc11001100.weavergirl.api.observability.MetricCollector collector) {}
        };
    com.github.cc11001100.weavergirl.api.observability.ResourceAttributeProvider provider =
        () -> java.util.Collections.singletonMap("plugin", "test-plugin");
    com.github.cc11001100.weavergirl.api.tracing.SpanProcessor processor =
        new com.github.cc11001100.weavergirl.api.tracing.SpanProcessor() {
          @Override
          public String name() {
            return "test-processor";
          }

          @Override
          public com.github.cc11001100.weavergirl.api.tracing.SpanContext process(
              com.github.cc11001100.weavergirl.api.tracing.SpanContext span) {
            return span;
          }
        };

    com.github.cc11001100.weavergirl.api.observability.HealthCheck healthCheck =
        new com.github.cc11001100.weavergirl.api.observability.HealthCheck() {
          @Override
          public String name() {
            return "test-health";
          }

          @Override
          public com.github.cc11001100.weavergirl.api.observability.HealthCheckResult check() {
            return com.github.cc11001100.weavergirl.api.observability.HealthCheckResult.up();
          }
        };
    com.github.cc11001100.weavergirl.api.alert.AlertChannel channel = alert -> {};
    com.github.cc11001100.weavergirl.api.alert.AlertRule rule =
        com.github.cc11001100.weavergirl.api.alert.AlertRule.builder()
            .name("test-rule")
            .metric("test_metric")
            .threshold(1)
            .build();

    DefaultPluginContext context =
        new DefaultPluginContext(mockRegistry, null, "test-plugin");
    context.registerSpanSink(sink);
    context.registerSpanProcessor(processor);
    context.registerHealthCheck(healthCheck);
    context.registerAlertChannel(channel);
    context.registerAlertRule(rule);
    context.registerMetricSource(source);
    context.registerResourceAttributes(provider);

    assertTrue(
        com.github.cc11001100.weavergirl.api.observability.SpanSinks.getSinks().contains(sink));
    assertTrue(
        com.github.cc11001100.weavergirl.api.tracing.SpanProcessors.getProcessors()
            .contains(processor));
    assertTrue(
        com.github.cc11001100.weavergirl.api.observability.HealthChecks.getChecks()
            .contains(healthCheck));
    assertTrue(
        com.github.cc11001100.weavergirl.api.alert.AlertEngine.getChannels().contains(channel));
    assertTrue(
        com.github.cc11001100.weavergirl.api.alert.AlertEngine.getRules().contains(rule));
    assertTrue(
        com.github.cc11001100.weavergirl.api.observability.MetricSources.getSources()
            .contains(source));
    assertEquals(
        "test-plugin",
        com.github.cc11001100.weavergirl.api.observability.ResourceAttributes.getAttributes()
            .get("plugin"));

    context.withdrawServices();

    assertFalse(
        com.github.cc11001100.weavergirl.api.observability.SpanSinks.getSinks().contains(sink),
        "an unloaded plugin must not keep receiving spans");
    assertFalse(
        com.github.cc11001100.weavergirl.api.tracing.SpanProcessors.getProcessors()
            .contains(processor),
        "an unloaded plugin must not keep rewriting spans");
    assertFalse(
        com.github.cc11001100.weavergirl.api.observability.HealthChecks.getChecks()
            .contains(healthCheck),
        "an unloaded plugin must not keep answering health probes");
    assertFalse(
        com.github.cc11001100.weavergirl.api.alert.AlertEngine.getChannels().contains(channel),
        "an unloaded plugin must not keep receiving alerts");
    assertFalse(
        com.github.cc11001100.weavergirl.api.alert.AlertEngine.getRules().contains(rule),
        "an unloaded plugin must not keep firing alerts");
    assertFalse(
        com.github.cc11001100.weavergirl.api.observability.MetricSources.getSources()
            .contains(source),
        "an unloaded plugin must not keep being scraped");
    assertFalse(
        com.github.cc11001100.weavergirl.api.observability.ResourceAttributes.getAttributes()
            .containsKey("plugin"),
        "an unloaded plugin must not keep contributing resource attributes");
  }
}
