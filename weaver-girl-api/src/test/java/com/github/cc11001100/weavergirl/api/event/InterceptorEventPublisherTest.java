package com.github.cc11001100.weavergirl.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A failing listener or exporter is reported and does not stop the next consumer. */
class InterceptorEventPublisherTest {

  @AfterEach
  void reset() {
    InterceptorEventPublisher.getInstance().clearHistory();
    Tracer.clearCurrentSpan();
  }

  @Test
  void publishStampsTheActiveSpanAndSurvivesAThrowingListener() {
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    Tracer.startSpan("publish");
    final AtomicInteger seen = new AtomicInteger();
    InterceptorEventListener broken =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            throw new IllegalStateException("listener failed");
          }
        };
    InterceptorEventListener recording =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            seen.incrementAndGet();
          }
        };
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));
    publisher.addListener(broken);
    publisher.addListener(recording);
    publisher.clearHistory();
    try {
      publisher.publish(null);
      publisher.publish(LifecycleEvents.plugin(LifecycleEvents.PHASE_INIT, "demo", true, null));

      assertEquals(1, seen.get());
      assertEquals(2, publisher.getListenerCount());
      assertTrue(publisher.getListeners().contains(recording) || publisher.getListeners().contains(broken));
      InterceptorEvent recorded = publisher.getHistory().get(0);
      assertEquals(Tracer.getCurrentSpan().getTraceId(), recorded.getAttributes().get("traceId"));
      assertEquals("", recorded.getAttributes().get("detail"));
      assertTrue(captured.toString().contains("listener failed"));
    } finally {
      System.setErr(original);
      publisher.removeListener(broken);
      publisher.removeListener(recording);
    }
  }

  @Test
  void aThrowingExporterDoesNotDropTheHistory() {
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    DataExporter exporter =
        new DataExporter() {
          @Override
          public String name() {
            return "broken-exporter";
          }

          @Override
          public void export(InterceptorEvent event) {
            throw new IllegalStateException("exporter failed");
          }
        };
    ExporterRegistry.register(exporter);
    ExporterRegistry.activate(exporter.name());
    publisher.clearHistory();
    try {
      publisher.publish(
          LifecycleEvents.registry(LifecycleEvents.PHASE_REGISTER, "hook", false, "detail"));

      List<InterceptorEvent> history = publisher.getHistory();
      assertEquals(1, history.size());
      assertEquals("detail", history.get(0).getAttributes().get("detail"));
      assertTrue(ExporterRegistry.getActiveExporterNames().contains(exporter.name()));
    } finally {
      ExporterRegistry.deactivate(exporter.name());
      ExporterRegistry.unregister(exporter.name());
    }
  }
}
