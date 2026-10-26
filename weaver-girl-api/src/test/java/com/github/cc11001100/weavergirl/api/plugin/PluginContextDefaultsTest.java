package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.observability.HealthCheck;
import com.github.cc11001100.weavergirl.api.observability.HealthCheckResult;
import com.github.cc11001100.weavergirl.api.observability.HealthChecks;
import com.github.cc11001100.weavergirl.api.observability.MetricSource;
import com.github.cc11001100.weavergirl.api.observability.MetricSources;
import com.github.cc11001100.weavergirl.api.observability.ResourceAttributes;
import com.github.cc11001100.weavergirl.api.observability.SpanSink;
import com.github.cc11001100.weavergirl.api.observability.SpanSinks;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.sampling.SamplingStrategy;
import com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessor;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessors;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The default methods exist for test doubles of the interface. They register straight onto the
 * static registries and log to standard error.
 */
class PluginContextDefaultsTest {

  @AfterEach
  void reset() {
    PluginServices.clear();
    SpanSinks.clear();
    SpanProcessors.clear();
    MetricSources.clear();
    HealthChecks.clear();
    SamplingStrategyRegistry.clear();
    ResourceAttributes.clear();
    System.clearProperty("demo.key");
  }

  @Test
  void defaultLoggerWritesToStandardError() {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));
    try {
      PluginLogger logger = context("demo").getLogger();
      logger.info("hello");
      logger.warn("careful");
      logger.error("broken", new IllegalStateException("cause"));
      logger.error("broken-without-cause", null);
    } finally {
      System.setErr(original);
    }

    String text = captured.toString();
    assertTrue(text.contains("[demo] hello"), text);
    assertTrue(text.contains("WARN careful"), text);
    assertTrue(text.contains("ERROR broken"), text);
    assertTrue(text.contains("IllegalStateException"), text);
  }

  @Test
  void defaultRegistrationsReachTheStaticRegistries() {
    PluginContext context = context("demo");
    SpanSink sink =
        new SpanSink() {
          @Override
          public String name() {
            return "demo";
          }

          @Override
          public void onSpanEnd(
              com.github.cc11001100.weavergirl.api.observability.SpanRecord span) {}
        };
    SpanProcessor processor =
        new SpanProcessor() {
          @Override
          public String name() {
            return "demo";
          }

          @Override
          public com.github.cc11001100.weavergirl.api.tracing.SpanContext process(
              com.github.cc11001100.weavergirl.api.tracing.SpanContext span) {
            return span;
          }
        };
    MetricSource source =
        new MetricSource() {
          @Override
          public String name() {
            return "demo";
          }

          @Override
          public void collect(
              com.github.cc11001100.weavergirl.api.observability.MetricCollector collector) {}
        };
    HealthCheck check =
        new HealthCheck() {
          @Override
          public String name() {
            return "demo";
          }

          @Override
          public HealthCheckResult check() {
            return HealthCheckResult.up();
          }
        };
    SamplingStrategy strategy =
        new SamplingStrategy() {
          @Override
          public String name() {
            return "demo";
          }

          @Override
          public boolean shouldSample() {
            return true;
          }
        };
    AlertChannel channel = alert -> {};
    AlertRule rule =
        AlertRule.builder().name("demo-rule").metric("demo_metric").threshold(1).build();

    context.registerService(CharSequence.class, "value");
    context.registerSpanSink(sink);
    context.registerSpanProcessor(processor);
    context.registerMetricSource(source);
    context.registerResourceAttributes(() -> Collections.singletonMap("region", "cn"));
    context.registerHealthCheck(check);
    context.registerSamplingStrategy(strategy);
    context.registerAlertChannel(channel);
    context.registerAlertRule(rule);

    assertEquals("value", PluginServices.get(CharSequence.class).get(0));
    assertTrue(SpanSinks.getSinks().contains(sink));
    assertTrue(SpanProcessors.getProcessors().contains(processor));
    assertTrue(MetricSources.getSources().contains(source));
    assertEquals("cn", ResourceAttributes.getAttributes().get("region"));
    assertTrue(HealthChecks.getChecks().contains(check));
    assertEquals("demo", SamplingStrategyRegistry.getActiveName());
    assertTrue(AlertEngine.getChannels().contains(channel));
    assertTrue(AlertEngine.getRules().contains(rule));

    AlertEngine.removeChannel(channel);
    AlertEngine.removeRule(rule);
  }

  @Test
  void classPresentChecksTheGivenLoader() {
    PluginContext context = context("demo");

    assertTrue(context.classPresent(String.class.getClassLoader(), "java.lang.String"));
    assertFalse(context.classPresent(null, "no.such.Class"));
    assertFalse(context.classPresent(String.class.getClassLoader(), ""));
    assertFalse(context.classPresent(String.class.getClassLoader(), null));
  }

  @Test
  void typedConfigAccessorsFallBackOnMissingOrInvalidValues() {
    PluginContext context = context("demo");

    assertEquals(5L, context.getConfigLong("demo.key", 5L));
    assertEquals(5, context.getConfigInt("demo.key", 5));
    assertTrue(context.getConfigBoolean("demo.key", true));
    assertEquals("a", context.getConfigEnum("demo.key", "a", "a", "b"));

    System.setProperty("demo.key", "not-a-number");
    assertEquals(5L, context.getConfigLong("demo.key", 5L));
    System.setProperty("demo.key", "maybe");
    assertFalse(context.getConfigBoolean("demo.key", false));
    System.setProperty("demo.key", "nope");
    assertEquals("a", context.getConfigEnum("demo.key", "a", "a", "b"));

    System.setProperty("demo.key", "12");
    assertEquals(12, context.getConfigInt("demo.key", 5));
    System.setProperty("demo.key", "true");
    assertTrue(context.getConfigBoolean("demo.key", false));
    System.setProperty("demo.key", "B");
    assertEquals("b", context.getConfigEnum("demo.key", "a", "a", "b"));
  }

  private static PluginContext context(final String name) {
    return new PluginContext() {
      @Override
      public InterceptorRegistry getRegistry() {
        return null;
      }

      @Override
      public String getConfig(String key) {
        return System.getProperty(key);
      }

      @Override
      public String getConfig(String key, String defaultValue) {
        String value = getConfig(key);
        return value != null ? value : defaultValue;
      }

      @Override
      public Map<String, String> getAllConfig() {
        return Collections.emptyMap();
      }

      @Override
      public String getPluginName() {
        return name;
      }
    };
  }
}
