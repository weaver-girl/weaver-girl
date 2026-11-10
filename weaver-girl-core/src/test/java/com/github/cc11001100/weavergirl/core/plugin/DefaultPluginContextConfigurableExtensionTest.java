package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.EventTransformers;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventTransformer;
import com.github.cc11001100.weavergirl.api.observability.ConfigurableExtension;
import com.github.cc11001100.weavergirl.api.observability.HealthCheck;
import com.github.cc11001100.weavergirl.api.observability.HealthCheckResult;
import com.github.cc11001100.weavergirl.api.observability.HealthChecks;
import com.github.cc11001100.weavergirl.api.observability.MetricPoint;
import com.github.cc11001100.weavergirl.api.observability.MetricProcessor;
import com.github.cc11001100.weavergirl.api.observability.MetricProcessors;
import com.github.cc11001100.weavergirl.api.observability.SpanRecord;
import com.github.cc11001100.weavergirl.api.observability.SpanSink;
import com.github.cc11001100.weavergirl.api.observability.SpanSinks;
import com.github.cc11001100.weavergirl.api.observability.TagEnricher;
import com.github.cc11001100.weavergirl.api.observability.TagEnrichers;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A {@link ConfigurableExtension} is asked once, at registration, whether it wants to be active.
 * Disabled extensions never enter their registry, so they cost nothing at runtime and are not
 * withdrawn later because they were never added.
 */
class DefaultPluginContextConfigurableExtensionTest {

  @BeforeEach
  @AfterEach
  void reset() {
    SpanSinks.clear();
    HealthChecks.clear();
    TagEnrichers.clear();
    MetricProcessors.clear();
    EventTransformers.clear();
  }

  @Test
  void aDisabledSpanSinkIsNotRegistered() {
    DefaultPluginContext context = context("sink.enabled", "false");

    context.registerSpanSink(new ConfigurableSpanSink("configurable", "sink.enabled"));

    assertTrue(SpanSinks.getSinks().isEmpty());
  }

  @Test
  void anEnabledSpanSinkIsRegistered() {
    DefaultPluginContext context = context("sink.enabled", "true");

    context.registerSpanSink(new ConfigurableSpanSink("configurable", "sink.enabled"));

    assertEquals(1, SpanSinks.getSinks().size());
  }

  @Test
  void theExtensionsOwnDefaultAppliesWhenTheKeyIsAbsent() {
    DefaultPluginContext context = context(null, null);

    context.registerSpanSink(new ConfigurableSpanSink("configurable", "sink.enabled"));

    assertEquals(1, SpanSinks.getSinks().size(),
        "isEnabled() falls back to its default when the key is not configured");
  }

  @Test
  void anExtensionWithoutTheInterfaceIsAlwaysRegistered() {
    DefaultPluginContext context = context("sink.enabled", "false");

    context.registerSpanSink(
        new SpanSink() {
          @Override
          public String name() {
            return "plain";
          }

          @Override
          public void onSpanEnd(SpanRecord span) {}
        });

    assertEquals(1, SpanSinks.getSinks().size());
  }

  @Test
  void withdrawingAContextThatRegisteredNothingIsHarmless() {
    DefaultPluginContext context = context("sink.enabled", "false");
    context.registerSpanSink(new ConfigurableSpanSink("configurable", "sink.enabled"));

    context.withdrawServices();

    assertTrue(SpanSinks.getSinks().isEmpty());
  }

  @Test
  void aThrowingPredicateCountsAsDisabledAndDoesNotBreakPluginInit() {
    DefaultPluginContext context = context(null, null);

    context.registerSpanSink(
        new ConfigurableSpanSink("broken", "sink.enabled") {
          @Override
          public boolean isEnabled(PluginContext pluginContext) {
            throw new IllegalStateException("bad predicate");
          }
        });

    assertTrue(SpanSinks.getSinks().isEmpty());
  }

  @Test
  void theGuardAppliesToEveryObservabilityRegistration() {
    DefaultPluginContext context = context("off", "false");

    context.registerHealthCheck(new ConfigurableHealthCheck("health", "off"));
    context.registerTagEnricher(new ConfigurableTagEnricher("enricher", "off"));
    context.registerMetricProcessor(new ConfigurableMetricProcessor("processor", "off"));
    context.registerEventTransformer(new ConfigurableEventTransformer("transformer", "off"));

    assertTrue(HealthChecks.getChecks().isEmpty());
    assertTrue(TagEnrichers.getEnrichers().isEmpty());
    assertTrue(MetricProcessors.isEmpty());
    assertTrue(EventTransformers.getTransformers().isEmpty());
    assertNull(HealthChecks.checkAll().getComponents().get("health"));
  }

  @Test
  void aDisabledProcessorNeverRuns() {
    DefaultPluginContext context = context("off", "false");
    context.registerMetricProcessor(new ConfigurableMetricProcessor("processor", "off"));

    MetricPoint out = MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1));

    assertNotNull(out, "no processor ran, so the sample passes through unchanged");
    assertEquals("m", out.name());
  }

  private static DefaultPluginContext context(String key, String value) {
    Map<String, String> config = new HashMap<>();
    if (key != null) {
      config.put(key, value);
    }
    return new DefaultPluginContext(new DefaultInterceptorRegistry(), config, "plugin-a");
  }

  private static class ConfigurableSpanSink implements SpanSink, ConfigurableExtension {
    private final String name;
    private final String key;

    ConfigurableSpanSink(String name, String key) {
      this.name = name;
      this.key = key;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String configKey() {
      return key;
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return context.getConfigBoolean(key, true);
    }

    @Override
    public void onSpanEnd(SpanRecord span) {}
  }

  private static final class ConfigurableHealthCheck
      implements HealthCheck, ConfigurableExtension {
    private final String name;
    private final String key;

    ConfigurableHealthCheck(String name, String key) {
      this.name = name;
      this.key = key;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String configKey() {
      return key;
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return context.getConfigBoolean(key, true);
    }

    @Override
    public HealthCheckResult check() {
      return HealthCheckResult.up();
    }
  }

  private static final class ConfigurableTagEnricher implements TagEnricher, ConfigurableExtension {
    private final String name;
    private final String key;

    ConfigurableTagEnricher(String name, String key) {
      this.name = name;
      this.key = key;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String configKey() {
      return key;
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return context.getConfigBoolean(key, true);
    }

    @Override
    public Map<String, String> enrichTags(SpanContext span) {
      return Collections.singletonMap("tag", "value");
    }
  }

  private static final class ConfigurableMetricProcessor
      implements MetricProcessor, ConfigurableExtension {
    private final String name;
    private final String key;

    ConfigurableMetricProcessor(String name, String key) {
      this.name = name;
      this.key = key;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String configKey() {
      return key;
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return context.getConfigBoolean(key, true);
    }

    @Override
    public MetricPoint process(MetricPoint point) {
      return null;
    }
  }

  private static final class ConfigurableEventTransformer
      implements InterceptorEventTransformer, ConfigurableExtension {
    private final String name;
    private final String key;

    ConfigurableEventTransformer(String name, String key) {
      this.name = name;
      this.key = key;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String configKey() {
      return key;
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return context.getConfigBoolean(key, true);
    }

    @Override
    public InterceptorEvent transform(InterceptorEvent event) {
      return event;
    }
  }
}
