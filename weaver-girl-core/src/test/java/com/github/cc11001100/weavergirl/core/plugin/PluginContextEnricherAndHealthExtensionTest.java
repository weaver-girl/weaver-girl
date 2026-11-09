package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.observability.ExtensionPerformanceTracker;
import com.github.cc11001100.weavergirl.api.observability.HealthCheckExtension;
import com.github.cc11001100.weavergirl.api.observability.HealthCheckResult;
import com.github.cc11001100.weavergirl.api.observability.HealthChecks;
import com.github.cc11001100.weavergirl.api.observability.HealthReport;
import com.github.cc11001100.weavergirl.api.observability.TagEnricher;
import com.github.cc11001100.weavergirl.api.observability.TagEnrichers;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessors;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A plugin's {@code PluginContext} is the only place the engine can learn which tag enrichers and
 * health-check extensions are a plugin's, so it can withdraw them on unload. These tests pin that
 * ownership: the names the registrations are reported under, and what unload leaves behind.
 */
class PluginContextEnricherAndHealthExtensionTest {

  @AfterEach
  void reset() {
    TagEnrichers.clear();
    HealthChecks.clear();
    ExtensionPerformanceTracker.clear();
    SpanProcessors.clear();
    Tracer.clearCurrentSpan();
  }

  @Test
  void anExtensionHealthCheckIsReportedUnderThePluginQualifiedName() {
    DefaultPluginContext context = context("plugin-a");

    context.registerHealthCheckExtension(extension("backend", HealthCheckResult::up));

    HealthReport report = HealthChecks.checkAll();
    assertEquals("[plugin-a/backend]", report.getComponents().keySet().toString());
    assertEquals(HealthCheckResult.Status.UP, report.getOverallStatus());
  }

  @Test
  void twoPluginsReportingTheSameComponentStayDistinct() {
    context("plugin-a").registerHealthCheckExtension(extension("backend", HealthCheckResult::up));
    context("plugin-b")
        .registerHealthCheckExtension(
            extension("backend", () -> HealthCheckResult.down("offline")));

    HealthReport report = HealthChecks.checkAll();

    assertEquals(2, report.getComponentCount());
    assertEquals(HealthCheckResult.Status.DOWN, report.getComponents().get("plugin-b/backend").getStatus());
    assertEquals(HealthCheckResult.Status.UP, report.getComponents().get("plugin-a/backend").getStatus());
  }

  @Test
  void aRegisteredEnricherTagsSpansEndedWhileThePluginIsLoaded() {
    DefaultPluginContext context = context("plugin-a");
    context.registerTagEnricher(enricher("tenant", span -> Collections.singletonMap("tenant.id", "t-1")));

    Tracer.startSpan("op");
    SpanContext ended = Tracer.endSpan("op", "OK");

    assertNotNull(ended);
    assertEquals("t-1", ended.getAttributes().get("tenant.id"));
  }

  @Test
  void registeringTheSameEnricherTwiceThroughOneContextAddsItOnce() {
    DefaultPluginContext context = context("plugin-a");
    TagEnricher enricher = enricher("tenant", span -> Collections.<String, String>emptyMap());

    context.registerTagEnricher(enricher);
    context.registerTagEnricher(enricher);

    assertEquals(1, TagEnrichers.getEnrichers().size());
  }

  @Test
  void withdrawRemovesBothExtensionsAndTheirCountersButKeepsOtherPlugins() {
    DefaultPluginContext owner = context("owner");
    DefaultPluginContext other = context("other");
    HealthCheckExtension ownerHealth = extension("backend", HealthCheckResult::up);
    HealthCheckExtension otherHealth = extension("backend", HealthCheckResult::up);
    TagEnricher ownerEnricher = enricher("tenant", span -> Collections.singletonMap("tenant.id", "t"));
    TagEnricher otherEnricher = enricher("tenant", span -> Collections.singletonMap("tenant.id", "t"));

    owner.registerHealthCheckExtension(ownerHealth);
    other.registerHealthCheckExtension(otherHealth);
    owner.registerTagEnricher(ownerEnricher);
    other.registerTagEnricher(otherEnricher);

    // Generate counters for the owner's extensions, so withdrawal has something to clean up.
    HealthChecks.checkAll();
    Tracer.startSpan("op");
    Tracer.endSpan("op", "OK");
    assertNotNull(
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_TAG_ENRICHER, "tenant"));

    owner.withdrawServices();

    assertEquals(1, HealthChecks.getExtensions().size());
    assertSame(otherHealth, HealthChecks.getExtensions().get(0));
    assertEquals(1, TagEnrichers.getEnrichers().size());
    assertSame(otherEnricher, TagEnrichers.getEnrichers().get(0));
    assertFalse(HealthChecks.checkAll().getComponents().containsKey("owner/backend"));
    assertTrue(HealthChecks.checkAll().getComponents().containsKey("other/backend"));

    // The owner's health series is gone; the other plugin's counters survive.
    assertNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK, "owner/backend"));
    assertNotNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK, "other/backend"));
  }

  @Test
  void withdrawIsSafeWhenNothingWasRegistered() {
    DefaultPluginContext context = context("empty");

    assertDoesNotThrow(context::withdrawServices);
  }

  private static DefaultPluginContext context(String pluginName) {
    return new DefaultPluginContext(leftRegistry(), new HashMap<String, String>(), pluginName);
  }

  private static DefaultInterceptorRegistry leftRegistry() {
    // Unused by these registrations; a fresh registry keeps the context's constructor happy.
    return new DefaultInterceptorRegistry();
  }

  private static HealthCheckExtension extension(
      String name, java.util.function.Supplier<HealthCheckResult> body) {
    return new HealthCheckExtension() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public HealthCheckResult checkHealth() {
        return body.get();
      }
    };
  }

  private static TagEnricher enricher(
      String name, java.util.function.Function<SpanContext, Map<String, String>> body) {
    return new TagEnricher() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Map<String, String> enrichTags(SpanContext span) {
        return body.apply(span);
      }
    };
  }
}
