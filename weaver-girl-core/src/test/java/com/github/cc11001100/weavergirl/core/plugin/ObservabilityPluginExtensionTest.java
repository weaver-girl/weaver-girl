package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import com.github.cc11001100.weavergirl.api.observability.HealthCheck;
import com.github.cc11001100.weavergirl.api.observability.HealthCheckResult;
import com.github.cc11001100.weavergirl.api.observability.HealthChecks;
import com.github.cc11001100.weavergirl.api.observability.MetricSource;
import com.github.cc11001100.weavergirl.api.observability.MetricSources;
import com.github.cc11001100.weavergirl.api.observability.ResourceAttributes;
import com.github.cc11001100.weavergirl.api.observability.SpanSink;
import com.github.cc11001100.weavergirl.api.observability.SpanSinks;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginServices;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.sampling.SamplingStrategy;
import com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessor;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessors;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plugins extend observability through the public {@link PluginContext}. These tests drive that
 * context, {@link InterceptorEventPublisher#publish}, and {@link PluginLoader} itself.
 */
class ObservabilityPluginExtensionTest {

  @RegisterExtension
  static final TestWatcher REPORTER =
      new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
          System.out.println("PASSED " + context.getDisplayName());
        }
      };

  @TempDir Path tempDir;

  private List<InterceptorEventListener> listenersBefore;

  @BeforeEach
  void snapshotListeners() {
    listenersBefore =
        new ArrayList<InterceptorEventListener>(
            InterceptorEventPublisher.getInstance().getListeners());
  }

  @AfterEach
  void resetGlobalRegistries() {
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    for (InterceptorEventListener listener :
        new ArrayList<InterceptorEventListener>(publisher.getListeners())) {
      if (!containsInstance(listenersBefore, listener)) {
        publisher.removeListener(listener);
      }
    }
    publisher.clearHistory();
    ExporterRegistry.shutdownAll();
    Tracer.clearCurrentSpan();
    SamplingStrategyRegistry.clear();
    SpanSinks.clear();
    SpanProcessors.clear();
    MetricSources.clear();
    HealthChecks.clear();
    ResourceAttributes.clear();
    AlertEngine.clear();
    PluginServices.clear();
  }

  @Test
  @DisplayName(
      "context registration delivers one event with the current span ids and isolates a throwing consumer")
  void contextRegistrationDeliversOnceWithSpanAndIsolatesFailures() {
    final List<InterceptorEvent> seen = new CopyOnWriteArrayList<InterceptorEvent>();
    final AtomicInteger exports = new AtomicInteger();
    final List<InterceptorEvent> exported = new CopyOnWriteArrayList<InterceptorEvent>();
    WeaverPlugin plugin =
        new WeaverPlugin() {
          private final InterceptorEventListener listener =
              new InterceptorEventListener() {
                @Override
                public void onEvent(InterceptorEvent event) {
                  seen.add(event);
                }
              };

          @Override
          public String name() {
            return "api-only";
          }

          @Override
          public void init(PluginContext context) {
            context.registerEventListener(listener);
            context.registerEventListener(listener);
            context.registerDataExporter(
                new DataExporter() {
                  @Override
                  public String name() {
                    return "api-only";
                  }

                  @Override
                  public void export(InterceptorEvent event) {
                    exports.incrementAndGet();
                    exported.add(event);
                  }
                });
          }
        };
    DefaultPluginContext context = context("api-only");
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    int before = publisher.getListenerCount();
    SpanContext span = Tracer.startSpan("charge");
    try {
      plugin.init(context);
      assertEquals(before + 1, publisher.getListenerCount(), "the same listener was added twice");
      assertTrue(ExporterRegistry.getActiveExporterNames().contains("api-only"));

      final AtomicInteger healthyListens = new AtomicInteger();
      final AtomicInteger healthyExports = new AtomicInteger();
      context.registerEventListener(
          new InterceptorEventListener() {
            @Override
            public void onEvent(InterceptorEvent event) {
              throw new IllegalStateException("listener down");
            }
          });
      context.registerEventListener(
          new InterceptorEventListener() {
            @Override
            public void onEvent(InterceptorEvent event) {
              healthyListens.incrementAndGet();
            }
          });
      context.registerDataExporter(throwingExporter("boom-export"));
      context.registerDataExporter(countingExporter("healthy-export", healthyExports));

      assertDoesNotThrow(
          new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
              publisher.publish(event("span-probe"));
            }
          });

      assertEquals(1, seen.size());
      assertEquals(1, exports.get());
      assertEquals(span.getTraceId(), seen.get(0).getAttributes().get(InterceptorEvent.TRACE_ID));
      assertEquals(span.getSpanId(), seen.get(0).getAttributes().get(InterceptorEvent.SPAN_ID));
      assertEquals(
          span.getTraceId(), exported.get(0).getAttributes().get(InterceptorEvent.TRACE_ID));
      assertEquals(
          span.getSpanId(), exported.get(0).getAttributes().get(InterceptorEvent.SPAN_ID));
      assertEquals(1, healthyListens.get());
      assertEquals(1, healthyExports.get());
    } finally {
      Tracer.clearCurrentSpan();
      context.withdrawServices();
    }

    publisher.publish(event("after-withdraw"));
    assertEquals(1, seen.size(), "the withdrawn listener was notified again");
    assertEquals(1, exports.get(), "the withdrawn exporter was called again");
    assertNull(ExporterRegistry.get("api-only"));
  }

  @Test
  @DisplayName(
      "withdraw removes only this plugin and keeps a same-named replacement that is still in use")
  void withdrawKeepsReplacementAndOtherPluginsContributions() {
    final AtomicInteger firstExports = new AtomicInteger();
    final AtomicInteger replacementExports = new AtomicInteger();
    final AtomicInteger otherListens = new AtomicInteger();
    final AtomicInteger otherExports = new AtomicInteger();
    DataExporter first = equalByName("shared-export", firstExports);
    DataExporter replacement = equalByName("shared-export", replacementExports);
    DefaultPluginContext owner = context("owner");
    DefaultPluginContext other = context("other");

    final AtomicInteger ownerListens = new AtomicInteger();
    owner.registerEventListener(countingListener(ownerListens));
    owner.registerDataExporter(first);
    SpanSink ownerSink = sink("owner-sink");
    SpanProcessor ownerProcessor = processor("owner-processor");
    MetricSource ownerSource = source("owner-source");
    HealthCheck ownerHealth = health("owner-health");
    AlertChannel ownerChannel = channel();
    AlertRule ownerRule = rule("owner-rule");
    SamplingStrategy ownerStrategy = strategy("owner-strategy");
    owner.registerSpanSink(ownerSink);
    owner.registerSpanProcessor(ownerProcessor);
    owner.registerMetricSource(ownerSource);
    owner.registerResourceAttributes(
        new com.github.cc11001100.weavergirl.api.observability.ResourceAttributeProvider() {
          @Override
          public Map<String, String> resourceAttributes() {
            return Collections.singletonMap("from-owner", "yes");
          }
        });
    owner.registerHealthCheck(ownerHealth);
    owner.registerAlertChannel(ownerChannel);
    owner.registerAlertRule(ownerRule);
    owner.registerSamplingStrategy(ownerStrategy);

    ExporterRegistry.register(replacement);
    other.registerEventListener(countingListener(otherListens));
    other.registerDataExporter(countingExporter("other-export", otherExports));
    SpanSink otherSink = sink("other-sink");
    SpanProcessor otherProcessor = processor("other-processor");
    MetricSource otherSource = source("other-source");
    HealthCheck otherHealth = health("other-health");
    AlertChannel otherChannel = channel();
    AlertRule otherRule = rule("other-rule");
    SamplingStrategy otherStrategy = strategy("other-strategy");
    other.registerSpanSink(otherSink);
    other.registerSpanProcessor(otherProcessor);
    other.registerMetricSource(otherSource);
    other.registerResourceAttributes(
        new com.github.cc11001100.weavergirl.api.observability.ResourceAttributeProvider() {
          @Override
          public Map<String, String> resourceAttributes() {
            return Collections.singletonMap("from-other", "yes");
          }
        });
    other.registerHealthCheck(otherHealth);
    other.registerAlertChannel(otherChannel);
    other.registerAlertRule(otherRule);
    other.registerSamplingStrategy(otherStrategy);

    owner.withdrawServices();
    InterceptorEventPublisher.getInstance().publish(event("kept"));

    assertEquals(0, ownerListens.get());
    assertEquals(0, firstExports.get());
    assertEquals(1, replacementExports.get(), "the replacement exporter stopped receiving");
    assertSame(replacement, ExporterRegistry.get("shared-export"));
    assertEquals(1, otherListens.get());
    assertEquals(1, otherExports.get());
    assertFalse(SpanSinks.getSinks().contains(ownerSink));
    assertTrue(SpanSinks.getSinks().contains(otherSink));
    assertFalse(SpanProcessors.getProcessors().contains(ownerProcessor));
    assertTrue(SpanProcessors.getProcessors().contains(otherProcessor));
    assertFalse(MetricSources.getSources().contains(ownerSource));
    assertTrue(MetricSources.getSources().contains(otherSource));
    assertFalse(ResourceAttributes.getAttributes().containsKey("from-owner"));
    assertEquals("yes", ResourceAttributes.getAttributes().get("from-other"));
    assertFalse(HealthChecks.getChecks().contains(ownerHealth));
    assertTrue(HealthChecks.getChecks().contains(otherHealth));
    assertFalse(AlertEngine.getChannels().contains(ownerChannel));
    assertTrue(AlertEngine.getChannels().contains(otherChannel));
    assertFalse(AlertEngine.getRules().contains(ownerRule));
    assertTrue(AlertEngine.getRules().contains(otherRule));
    assertNull(SamplingStrategyRegistry.get("owner-strategy"));
    assertSame(otherStrategy, SamplingStrategyRegistry.get("other-strategy"));
    assertEquals("other-strategy", SamplingStrategyRegistry.getActiveName());
  }

  @Test
  @DisplayName("static registry receives nothing until activate, then receives the published event")
  void staticRegistryDoesNotExportUntilActivated() {
    final AtomicInteger exports = new AtomicInteger();
    DataExporter exporter = countingExporter("static-only", exports);
    ExporterRegistry.register(exporter);
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();

    publisher.publish(event("before-activate"));
    assertEquals(0, exports.get(), "a registered but inactive exporter received an event");

    assertTrue(ExporterRegistry.activate(exporter.name()));
    publisher.publish(event("after-activate"));
    assertEquals(1, exports.get());
  }

  @Test
  @DisplayName("direct addListener still notifies every registration of the same instance")
  void directAddListenerStillNotifiesEveryRegistration() {
    final AtomicInteger seen = new AtomicInteger();
    InterceptorEventListener listener = countingListener(seen);
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    publisher.addListener(listener);
    publisher.addListener(listener);
    publisher.publish(event("direct"));
    assertEquals(2, seen.get());
    publisher.removeListener(listener);
    publisher.removeListener(listener);
  }

  @Test
  @DisplayName("hot unload stops the plugin listener and exporter, and destroy stays idempotent")
  void hotUnloadStopsDelivery() throws Exception {
    Path plugins = tempDir.resolve("hot");
    Path sink = tempDir.resolve("hot-sink");
    Files.createDirectories(plugins);
    Files.createDirectories(sink);
    pluginJar(
        plugins,
        "hot.jar",
        recordingSource("HotPlugin", "hot-plugin", false, false, true));
    PluginLoader loader = new PluginLoader();
    int before = InterceptorEventPublisher.getInstance().getListenerCount();
    List<WeaverPlugin> loaded = load(loader, plugins, sink);
    assertEquals(1, loaded.size());
    assertEquals(before + 1, InterceptorEventPublisher.getInstance().getListenerCount());

    publish("PROBE_BEFORE");
    String afterBefore = read(sink.resolve("hot-plugin"));
    assertTrue(afterBefore.contains("INIT"), afterBefore);
    assertEquals(1, count(afterBefore, "L PROBE_BEFORE"), afterBefore);
    assertEquals(1, count(afterBefore, "E PROBE_BEFORE"), afterBefore);

    assertTrue(new File(plugins.toFile(), "hot.jar").delete());
    List<WeaverPlugin> removed = loader.unloadMissingJars(plugins.toString());
    assertTrue(names(removed).contains("hot-plugin"), names(removed).toString());
    publish("PROBE_AFTER");
    String afterUnload = read(sink.resolve("hot-plugin"));
    assertFalse(afterUnload.contains("PROBE_AFTER"), afterUnload);
    assertNull(ExporterRegistry.get("obs-hot-plugin"));
    assertEquals(before, InterceptorEventPublisher.getInstance().getListenerCount());
    assertDoesNotThrow(
        new org.junit.jupiter.api.function.Executable() {
          @Override
          public void execute() {
            loaded.get(0).destroy();
          }
        });
  }

  @Test
  @DisplayName("destroyAll stops delivery and a second destroy does not throw")
  void destroyAllStopsDeliveryAndDestroyStaysIdempotent() throws Exception {
    Path plugins = tempDir.resolve("shutdown");
    Path sink = tempDir.resolve("shutdown-sink");
    Files.createDirectories(plugins);
    Files.createDirectories(sink);
    pluginJar(
        plugins,
        "shutdown.jar",
        recordingSource("ShutdownPlugin", "shutdown-plugin", false, false, true));
    PluginLoader loader = new PluginLoader();
    int before = InterceptorEventPublisher.getInstance().getListenerCount();
    List<WeaverPlugin> loaded = load(loader, plugins, sink);
    publish("PROBE_BEFORE");
    assertEquals(1, count(read(sink.resolve("shutdown-plugin")), "L PROBE_BEFORE"));

    loader.destroyAll();
    publish("PROBE_AFTER");
    String text = read(sink.resolve("shutdown-plugin"));
    assertFalse(text.contains("PROBE_AFTER"), text);
    assertNull(ExporterRegistry.get("obs-shutdown-plugin"));
    assertEquals(before, InterceptorEventPublisher.getInstance().getListenerCount());
    assertDoesNotThrow(
        new org.junit.jupiter.api.function.Executable() {
          @Override
          public void execute() {
            loaded.get(0).destroy();
          }
        });
  }

  @Test
  @DisplayName("a plugin that disables itself during load no longer receives events")
  void disabledPluginDoesNotKeepReceiving() throws Exception {
    assertInactive(
        "disabled",
        "OffPlugin",
        "off-plugin",
        "off.jar",
        recordingSource("OffPlugin", "off-plugin", true, false, false));
  }

  @Test
  @DisplayName("a plugin whose library is missing no longer receives events")
  void missingLibraryPluginDoesNotKeepReceiving() throws Exception {
    assertInactive(
        "missing",
        "MissingPlugin",
        "missing-plugin",
        "missing.jar",
        recordingSource("MissingPlugin", "missing-plugin", false, true, false));
  }

  @Test
  @DisplayName("parallel load retires a disabled plugin and a plugin with nothing to instrument")
  void parallelLoadRetiresDisabledAndMissingPlugins() throws Exception {
    Path plugins = tempDir.resolve("parallel");
    Path sink = tempDir.resolve("parallel-sink");
    Files.createDirectories(plugins);
    Files.createDirectories(sink);
    pluginJar(
        plugins,
        "off.jar",
        recordingSource("ParallelOff", "parallel-off", true, false, false));
    pluginJar(
        plugins,
        "missing.jar",
        recordingSource("ParallelMissing", "parallel-missing", false, true, false));
    pluginJar(plugins, "keeper.jar", emptySource("KeeperPlugin", "keeper"));

    PluginLoader loader = new PluginLoader();
    int before = InterceptorEventPublisher.getInstance().getListenerCount();
    List<WeaverPlugin> loaded = load(loader, plugins, sink);
    try {
      assertTrue(names(loaded).contains("keeper"), names(loaded).toString());
      assertFalse(names(loaded).contains("parallel-off"));
      assertFalse(names(loaded).contains("parallel-missing"));
      publish("PROBE");
      assertInitWithoutProbe(sink.resolve("parallel-off"));
      assertInitWithoutProbe(sink.resolve("parallel-missing"));
      assertNull(ExporterRegistry.get("obs-parallel-off"));
      assertNull(ExporterRegistry.get("obs-parallel-missing"));
      assertEquals(before, InterceptorEventPublisher.getInstance().getListenerCount());
    } finally {
      loader.destroyAll();
    }
  }

  @Test
  @DisplayName("a plugin that throws from init does not keep the exporter it already registered")
  void failedInitDoesNotKeepTheExporter() throws Exception {
    Path plugins = tempDir.resolve("broken");
    Path sink = tempDir.resolve("broken-sink");
    Files.createDirectories(plugins);
    Files.createDirectories(sink);
    pluginJar(plugins, "broken.jar", failingSource("BrokenPlugin", "broken-plugin"));
    PluginLoader loader = new PluginLoader();
    int before = InterceptorEventPublisher.getInstance().getListenerCount();
    List<WeaverPlugin> loaded = load(loader, plugins, sink);
    try {
      assertFalse(names(loaded).contains("broken-plugin"));
      publish("PROBE");
      assertInitWithoutProbe(sink.resolve("broken-plugin"));
      assertNull(ExporterRegistry.get("obs-broken-plugin"));
      assertEquals(before, InterceptorEventPublisher.getInstance().getListenerCount());
    } finally {
      loader.destroyAll();
    }
  }

  private void assertInactive(
      String directory, String simple, String pluginName, String jarName, String source)
      throws Exception {
    Path plugins = tempDir.resolve(directory);
    Path sink = tempDir.resolve(directory + "-sink");
    Files.createDirectories(plugins);
    Files.createDirectories(sink);
    pluginJar(plugins, jarName, source);
    PluginLoader loader = new PluginLoader();
    int before = InterceptorEventPublisher.getInstance().getListenerCount();
    List<WeaverPlugin> loaded = load(loader, plugins, sink);
    try {
      assertFalse(names(loaded).contains(pluginName), names(loaded).toString());
      publish("PROBE");
      assertInitWithoutProbe(sink.resolve(pluginName));
      assertNull(ExporterRegistry.get("obs-" + pluginName));
      assertEquals(before, InterceptorEventPublisher.getInstance().getListenerCount());
    } finally {
      loader.destroyAll();
    }
  }

  /**
   * {@code INIT} is written by the plugin after it registers, so the subscription existed. Events
   * published by other plugins still loading can arrive before this plugin is retired. {@code PROBE}
   * is published only after {@code loadPluginsFromDirectory} has returned.
   */
  private static void assertInitWithoutProbe(Path file) throws Exception {
    String text = read(file);
    assertTrue(text.contains("INIT"), text);
    assertFalse(text.contains("PROBE"), text);
  }

  private static List<WeaverPlugin> load(PluginLoader loader, Path plugins, Path sink) {
    Map<String, String> config = new HashMap<String, String>();
    config.put("sinkDir", sink.toString());
    return loader.loadPluginsFromDirectory(
        plugins.toString(), new DefaultInterceptorRegistry(), config);
  }

  private static void publish(String type) {
    InterceptorEventPublisher.getInstance().publish(event(type));
  }

  private static InterceptorEvent event(String type) {
    return InterceptorEvent.builder().type(type).plugin("probe").className("C").methodName("m").build();
  }

  private static DefaultPluginContext context(String name) {
    return new DefaultPluginContext(new DefaultInterceptorRegistry(), Collections.<String, String>emptyMap(), name);
  }

  private static InterceptorEventListener countingListener(final AtomicInteger counter) {
    return new InterceptorEventListener() {
      @Override
      public void onEvent(InterceptorEvent event) {
        counter.incrementAndGet();
      }
    };
  }

  private static DataExporter countingExporter(final String name, final AtomicInteger counter) {
    return new DataExporter() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void export(InterceptorEvent event) {
        counter.incrementAndGet();
      }
    };
  }

  private static DataExporter throwingExporter(final String name) {
    return new DataExporter() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void export(InterceptorEvent event) {
        throw new IllegalStateException("exporter down");
      }
    };
  }

  /** Two instances with one name that compare equal, so removal has to be by identity. */
  private static DataExporter equalByName(final String name, final AtomicInteger counter) {
    return new DataExporter() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void export(InterceptorEvent event) {
        counter.incrementAndGet();
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof DataExporter && name.equals(((DataExporter) other).name());
      }

      @Override
      public int hashCode() {
        return name.hashCode();
      }
    };
  }

  private static SpanSink sink(final String name) {
    return new SpanSink() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void onSpanEnd(com.github.cc11001100.weavergirl.api.observability.SpanRecord span) {}
    };
  }

  private static SpanProcessor processor(final String name) {
    return new SpanProcessor() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public SpanContext process(SpanContext span) {
        return span;
      }
    };
  }

  private static MetricSource source(final String name) {
    return new MetricSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void collect(com.github.cc11001100.weavergirl.api.observability.MetricCollector collector) {}
    };
  }

  private static HealthCheck health(final String name) {
    return new HealthCheck() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public HealthCheckResult check() {
        return HealthCheckResult.up();
      }
    };
  }

  private static AlertChannel channel() {
    return new AlertChannel() {
      @Override
      public void onAlert(com.github.cc11001100.weavergirl.api.alert.AlertEvent alert) {}
    };
  }

  private static AlertRule rule(String name) {
    return AlertRule.builder().name(name).metric(name).threshold(1).build();
  }

  private static SamplingStrategy strategy(final String name) {
    return new SamplingStrategy() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public boolean shouldSample() {
        return true;
      }
    };
  }

  private static boolean containsInstance(List<?> items, Object candidate) {
    for (Object item : items) {
      if (item == candidate) {
        return true;
      }
    }
    return false;
  }

  private static List<String> names(List<WeaverPlugin> plugins) {
    List<String> names = new ArrayList<String>();
    for (WeaverPlugin plugin : plugins) {
      names.add(plugin.name());
    }
    return names;
  }

  private static String read(Path file) throws Exception {
    if (!Files.exists(file)) {
      return "";
    }
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  private static int count(String text, String token) {
    int found = 0;
    int from = 0;
    while (true) {
      int at = text.indexOf(token, from);
      if (at < 0) {
        return found;
      }
      found++;
      from = at + token.length();
    }
  }

  private static String emptySource(String simple, String pluginName) {
    return "package fixture;\n"
        + "import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;\n"
        + "import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;\n"
        + "public class "
        + simple
        + " implements WeaverPlugin {\n"
        + "  public String name() { return \""
        + pluginName
        + "\"; }\n"
        + "  public void registerInterceptors(InterceptorRegistry registry) {}\n"
        + "}\n";
  }

  private static String failingSource(String simple, String pluginName) {
    return recordingSource(simple, pluginName, false, false, false)
        .replace(
            "append(sink, \"INIT\");",
            "append(sink, \"INIT\");\n    throw new IllegalStateException(\"cannot start\");");
  }

  private static String recordingSource(
      String simple, String pluginName, boolean disabled, boolean missing, boolean idempotent) {
    StringBuilder source = new StringBuilder();
    source.append("package fixture;\n");
    source.append("import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;\n");
    source.append("import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;\n");
    source.append("import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;\n");
    source.append("import com.github.cc11001100.weavergirl.api.exporter.DataExporter;\n");
    source.append("import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;\n");
    source.append("import com.github.cc11001100.weavergirl.api.plugin.PluginContext;\n");
    source.append("import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;\n");
    source.append("import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;\n");
    source.append("public class ").append(simple).append(" implements WeaverPlugin {\n");
    source.append("  private InterceptorEventListener listener;\n");
    source.append("  private String exporterName;\n");
    source.append("  public String name() { return \"").append(pluginName).append("\"; }\n");
    source.append("  public void init(PluginContext c) {\n");
    source.append("    final String sink = new java.io.File(c.getConfig(\"sinkDir\"), name()).getPath();\n");
    source.append("    exporterName = \"obs-\" + name();\n");
    source.append("    final String file = sink;\n");
    source.append("    listener = new InterceptorEventListener() {\n");
    source.append("      public void onEvent(InterceptorEvent event) { append(file, \"L \" + event.getType()); }\n");
    source.append("    };\n");
    source.append("    c.registerEventListener(listener);\n");
    source.append("    c.registerDataExporter(new DataExporter() {\n");
    source.append("      public String name() { return exporterName; }\n");
    source.append("      public void export(InterceptorEvent event) { append(file, \"E \" + event.getType()); }\n");
    source.append("    });\n");
    source.append("    append(sink, \"INIT\");\n");
    source.append("  }\n");
    if (disabled) {
      source.append("  public boolean isEnabled(PluginContext c) { return false; }\n");
    }
    if (missing) {
      source.append(
          "  public String[] instrumentedLibraries() { return new String[]{\"com.example.NotOnClasspath\"}; }\n");
    }
    if (idempotent) {
      source.append("  public void destroy() {\n");
      source.append("    if (listener == null) return;\n");
      source.append("    InterceptorEventPublisher.getInstance().removeListener(listener);\n");
      source.append("    ExporterRegistry.unregister(exporterName);\n");
      source.append("    listener = null;\n");
      source.append("  }\n");
    }
    source.append("  public void registerInterceptors(InterceptorRegistry registry) {}\n");
    source.append("  static void append(String path, String line) {\n");
    source.append("    try {\n");
    source.append("      java.nio.file.Files.write(java.nio.file.Paths.get(path),\n");
    source.append("        (line + \"\\n\").getBytes(java.nio.charset.StandardCharsets.UTF_8),\n");
    source.append("        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);\n");
    source.append("    } catch (java.io.IOException e) { throw new RuntimeException(e); }\n");
    source.append("  }\n");
    source.append("}\n");
    return source.toString();
  }

  private void pluginJar(Path directory, String fileName, String source) throws Exception {
    String simple = source.substring(source.indexOf("public class ") + "public class ".length());
    simple = simple.substring(0, simple.indexOf(' '));
    Path build = tempDir.resolve("build-" + simple);
    Path classes = build.resolve("classes");
    Files.createDirectories(classes);
    Path javaFile = build.resolve(simple + ".java");
    Files.write(javaFile, source.getBytes(StandardCharsets.UTF_8));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null);
    try {
      Boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  null,
                  java.util.Arrays.asList(
                      "-classpath",
                      System.getProperty("java.class.path"),
                      "-d",
                      classes.toString(),
                      "--release",
                      "8"),
                  null,
                  files.getJavaFileObjects(javaFile.toFile()))
              .call();
      assertEquals(Boolean.TRUE, ok, "fixture plugin failed to compile:\n" + source);
    } finally {
      files.close();
    }
    Path jar = directory.resolve(fileName);
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
      File[] classFiles = classes.resolve("fixture").toFile().listFiles();
      for (File classFile : classFiles) {
        if (classFile.getName().endsWith(".class")) {
          put(out, "fixture/" + classFile.getName(), classFile.toPath());
        }
      }
      put(
          out,
          "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin",
          ("fixture." + simple + "\n").getBytes(StandardCharsets.UTF_8));
      put(
          out,
          "META-INF/weaver-girl-plugin.properties",
          ("plugin.name=" + simple + "\n").getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void put(java.util.jar.JarOutputStream out, String name, Path file) throws Exception {
    put(out, name, Files.readAllBytes(file));
  }

  private static void put(java.util.jar.JarOutputStream out, String name, byte[] bytes)
      throws Exception {
    out.putNextEntry(new java.util.jar.JarEntry(name));
    out.write(bytes);
    out.closeEntry();
  }
}
