package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.EventTransformers;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventTransformer;
import com.github.cc11001100.weavergirl.api.observability.ExtensionPerformanceTracker;
import com.github.cc11001100.weavergirl.api.observability.MetricPoint;
import com.github.cc11001100.weavergirl.api.observability.MetricProcessor;
import com.github.cc11001100.weavergirl.api.observability.MetricProcessors;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Metric processors and event transformers registered through a plugin's {@code PluginContext} are
 * owned by that plugin: they take effect while it is loaded and disappear when it is unloaded.
 */
class PluginContextGovernanceExtensionsTest {

  @BeforeEach
  @AfterEach
  void reset() {
    MetricProcessors.clear();
    EventTransformers.clear();
    ExtensionPerformanceTracker.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
    for (InterceptorEventListener listener :
        new java.util.ArrayList<InterceptorEventListener>(
            InterceptorEventPublisher.getInstance().getListeners())) {
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  @Test
  void aMetricProcessorRegisteredThroughTheContextGovernsSamples() {
    DefaultPluginContext context = context("plugin-a");
    context.registerMetricProcessor(
        processor("renamer", point -> point.toBuilder().name("app_" + point.name()).build()));

    MetricPoint result = MetricProcessors.apply(MetricPoint.gauge("requests", null, null, 3));

    assertNotNull(result);
    assertEquals("app_requests", result.name());
  }

  @Test
  void anEventTransformerRegisteredThroughTheContextGovernsEvents() {
    DefaultPluginContext context = context("plugin-a");
    context.registerEventTransformer(
        transformer("redactor", event -> event.toBuilder().attribute("sql", "***").build()));
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    java.util.List<InterceptorEvent> seen = new java.util.ArrayList<>();
    publisher.addListener(seen::add);

    publisher.publish(
        InterceptorEvent.builder()
            .type("slow-query")
            .plugin("jdbc")
            .attribute("sql", "select 1")
            .build());

    assertEquals(1, seen.size());
    assertEquals("***", seen.get(0).getAttributes().get("sql"));
  }

  @Test
  void registeringTheSameInstanceTwiceThroughOneContextAddsItOnce() {
    DefaultPluginContext context = context("plugin-a");
    MetricProcessor processor = processor("one", point -> point);
    InterceptorEventTransformer transformer = transformer("one", event -> event);

    context.registerMetricProcessor(processor);
    context.registerMetricProcessor(processor);
    context.registerEventTransformer(transformer);
    context.registerEventTransformer(transformer);

    assertEquals(1, MetricProcessors.getProcessors().size());
    assertEquals(1, EventTransformers.getTransformers().size());
  }

  @Test
  void withdrawRemovesBothExtensionsAndTheirCountersButKeepsOtherPlugins() {
    DefaultPluginContext owner = context("owner");
    DefaultPluginContext other = context("other");
    MetricProcessor ownerProcessor = processor("owner-governor", point -> point);
    MetricProcessor otherProcessor = processor("other-governor", point -> point);
    InterceptorEventTransformer ownerTransformer = transformer("owner-redactor", event -> event);
    InterceptorEventTransformer otherTransformer = transformer("other-redactor", event -> event);

    owner.registerMetricProcessor(ownerProcessor);
    other.registerMetricProcessor(otherProcessor);
    owner.registerEventTransformer(ownerTransformer);
    other.registerEventTransformer(otherTransformer);

    // Generate counters for both plugins' extensions, so withdrawal has something to clean up.
    MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1));
    EventTransformers.transform(InterceptorEvent.builder().type("t").build());
    assertNotNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR, "owner-governor"));
    assertNotNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_EVENT_TRANSFORMER, "owner-redactor"));

    owner.withdrawServices();

    assertEquals(1, MetricProcessors.getProcessors().size());
    assertSame(otherProcessor, MetricProcessors.getProcessors().get(0));
    assertEquals(1, EventTransformers.getTransformers().size());
    assertSame(otherTransformer, EventTransformers.getTransformers().get(0));

    // The owner's counters are gone; the other plugin's survive.
    assertNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR, "owner-governor"));
    assertNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_EVENT_TRANSFORMER, "owner-redactor"));
    assertNotNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR, "other-governor"));
    assertNotNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_EVENT_TRANSFORMER, "other-redactor"));

    // The surviving plugin's chain still runs.
    assertNotNull(MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1)));
    assertFalse(EventTransformers.getTransformers().isEmpty());
  }

  @Test
  void withdrawIsSafeWhenNothingWasRegistered() {
    DefaultPluginContext context = context("empty");

    context.withdrawServices();

    assertTrue(MetricProcessors.isEmpty());
    assertTrue(EventTransformers.getTransformers().isEmpty());
  }

  @Test
  void aNullRegistrationIsIgnored() {
    DefaultPluginContext context = context("plugin-a");

    context.registerMetricProcessor(null);
    context.registerEventTransformer(null);

    assertTrue(MetricProcessors.isEmpty());
    assertTrue(EventTransformers.getTransformers().isEmpty());
    assertNull(ExtensionPerformanceTracker.get(
        ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR, "unknown"));
  }

  private static DefaultPluginContext context(String pluginName) {
    return new DefaultPluginContext(
        new DefaultInterceptorRegistry(), new HashMap<String, String>(), pluginName);
  }

  private static MetricProcessor processor(
      String name, java.util.function.UnaryOperator<MetricPoint> body) {
    return new MetricProcessor() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public MetricPoint process(MetricPoint point) {
        return body.apply(point);
      }
    };
  }

  private static InterceptorEventTransformer transformer(
      String name, java.util.function.UnaryOperator<InterceptorEvent> body) {
    return new InterceptorEventTransformer() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public InterceptorEvent transform(InterceptorEvent event) {
        return body.apply(event);
      }
    };
  }
}