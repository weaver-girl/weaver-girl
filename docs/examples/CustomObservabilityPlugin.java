package com.github.cc11001100.weavergirl.docs.examples;

import com.github.cc11001100.weavergirl.api.observability.MetricCollector;
import com.github.cc11001100.weavergirl.api.observability.MetricSource;
import com.github.cc11001100.weavergirl.api.observability.SpanRecord;
import com.github.cc11001100.weavergirl.api.observability.SpanSink;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Example: a plugin that plugs into all four observability extension points.
 *
 * <ul>
 *   <li>{@link SpanProcessor} governs spans before anyone sees them: it drops health checks and
 *       removes credentials, so the redaction applies to the built-in exporter as well as to the
 *       sink below.
 *   <li>{@link SpanSink} receives every completed span. The sink only enqueues; a daemon thread
 *       ships batches to the backend so business threads are never blocked.
 *   <li>{@link MetricSource} is pulled at every Prometheus scrape, so it needs no thread of its own.
 *   <li>{@link com.github.cc11001100.weavergirl.api.observability.ResourceAttributeProvider}
 *       contributes runtime metadata to every OTLP export.
 * </ul>
 *
 * <p>All four are registered through {@link PluginContext}, which means the engine withdraws them
 * automatically when the plugin is unloaded or hot-replaced. Registering through the static
 * helpers works too, but then {@link #destroy()} has to unregister them by hand.
 *
 * <p>Configuration (defaults in parentheses):
 *
 * <pre>
 * customObservability=true          # master switch (true)
 * customObservabilityBuffer=4096    # spans held while the backend is slow (4096)
 * </pre>
 *
 * @since 1.0.0
 */
public class CustomObservabilityPlugin implements WeaverPlugin {

  private static final Logger LOG = Logger.getLogger(CustomObservabilityPlugin.class.getName());

  private final AtomicLong spansAccepted = new AtomicLong();
  private final AtomicLong spansDropped = new AtomicLong();

  private volatile boolean running;
  private BlockingQueue<SpanRecord> buffer;
  private Thread flusher;

  @Override
  public String name() {
    return "custom-observability";
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfigBoolean("customObservability", true);
  }

  @Override
  public void init(final PluginContext context) {
    if (!isEnabled(context)) {
      return;
    }

    int capacity = (int) context.getConfigLong("customObservabilityBuffer", 4096L);
    final BlockingQueue<SpanRecord> queue = new ArrayBlockingQueue<SpanRecord>(capacity);
    buffer = queue;
    running = true;

    flusher = new Thread(this::flushLoop, "custom-observability-flush");
    flusher.setDaemon(true);
    flusher.start();

    context.registerSpanProcessor(
        new SpanProcessor() {
          @Override
          public String name() {
            return "custom-observability-governance";
          }

          @Override
          public SpanContext process(SpanContext span) {
            // Runs on the application thread before every consumer: cheap attribute work only.
            String path = span.getAttribute("url.path");
            if ("/health".equals(path) || "/ready".equals(path)) {
              return null; // drop: neither the exporter nor the sink below will see it
            }
            SpanContext.Builder builder = span.toBuilder();
            builder.removeAttribute("http.header.authorization");
            return builder.build();
          }
        });

    context.registerSpanSink(
        new SpanSink() {
          @Override
          public String name() {
            return "custom-observability";
          }

          @Override
          public void onSpanEnd(SpanRecord span) {
            // Runs on the application thread: never block or do IO here.
            spansAccepted.incrementAndGet();
            if (!queue.offer(span)) {
              spansDropped.incrementAndGet();
            }
          }
        });

    context.registerMetricSource(
        new MetricSource() {
          @Override
          public String name() {
            return "custom-observability";
          }

          @Override
          public void collect(MetricCollector collector) {
            // Called on the Prometheus scrape thread; cheap snapshots only.
            collector.gauge(
                "custom_observability_buffer_depth", "Spans waiting for the backend", queue.size());
            collector.counter(
                "custom_observability_spans_accepted_total",
                "Spans handed to the sink",
                spansAccepted.get());
            collector.counter(
                "custom_observability_spans_dropped_total",
                "Spans dropped because the buffer was full",
                spansDropped.get());
          }
        });

    context.registerResourceAttributes(
        () -> {
          Map<String, String> attributes = new LinkedHashMap<String, String>();
          String region = System.getenv("DEPLOY_REGION");
          if (region != null && !region.trim().isEmpty()) {
            attributes.put("cloud.region", region);
          }
          return attributes;
        });
  }

  private void flushLoop() {
    // Capture the queue: destroy() may clear the field while this loop is winding down.
    BlockingQueue<SpanRecord> queue = buffer;
    List<SpanRecord> batch = new ArrayList<SpanRecord>(64);
    while (running) {
      try {
        SpanRecord first = queue.poll(1, TimeUnit.SECONDS);
        if (first == null) {
          continue;
        }
        batch.add(first);
        queue.drainTo(batch, 63);
        send(batch);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // One bad batch must not kill the flusher.
        LOG.log(Level.WARNING, "Failed to ship a span batch", e);
      } finally {
        batch.clear();
      }
    }
  }

  private void send(List<SpanRecord> batch) {
    // Your backend call goes here: encode batch and POST it, or hand it to an existing client.
    // The sink contract is satisfied as long as this method never throws out of the loop.
  }

  @Override
  public void destroy() {
    // The context withdraws the sink, metric source and resource provider on unload. This method
    // only owns the buffer thread and any connection it opened.
    running = false;
    if (flusher != null) {
      flusher.interrupt();
      flusher = null;
    }
    buffer = null;
  }
}
