package com.github.cc11001100.weavergirl.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.taint.CommandExecutionSink;
import com.github.cc11001100.weavergirl.api.taint.SqlExecutionSink;
import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintFindings;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** IAST findings increment a per-sink counter and keep slice attributes on the event. */
class IastFindingMetricsTest {

  @AfterEach
  void cleanup() {
    Tracer.clearCurrentSpan();
    for (int i = 0; i < 4; i++) {
      Taint.closeScope();
    }
  }

  @Test
  void countersSplitBySinkKindAndLeaveSliceAttributesOnTheEvent() {
    PrometheusExporter exporter = new PrometheusExporter();
    assertFalse(exporter.renderMetrics().contains("weavergirl_iast_findings_total"));

    final List<InterceptorEvent> seen = new ArrayList<InterceptorEvent>();
    InterceptorEventListener collector =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
              seen.add(event);
            }
          }
        };
    InterceptorEventPublisher.getInstance().addListener(exporter);
    InterceptorEventPublisher.getInstance().addListener(collector);
    try {
      Taint.openScope();
      Tracer.startSpan();
      String commandArg = new String("cmd-value");
      String sql = new String("SELECT secret");
      Taint.tag(commandArg, "http.parameter:cmd");
      Taint.tag(sql, "http.parameter:q");
      CommandExecutionSink.observe("clean");
      CommandExecutionSink.observe(commandArg);
      SqlExecutionSink.observe(sql);

      String metrics = exporter.renderMetrics();
      assertTrue(
          metrics.contains("weavergirl_iast_findings_total{sink=\"command-execution\"} 1"));
      assertTrue(metrics.contains("weavergirl_iast_findings_total{sink=\"sql\"} 1"));
      assertFalse(metrics.contains("weavergirl_iast_findings_total{sink=\"command-execution\"} 2"));
      assertEquals(2, seen.size());
      assertEquals("http.parameter:cmd", seen.get(0).getAttributes().get("source"));
      assertEquals("0", seen.get(0).getAttributes().get("start"));
      assertEquals(Integer.toString(commandArg.length()), seen.get(0).getAttributes().get("end"));
      assertEquals(commandArg, seen.get(0).getAttributes().get("argument"));
      assertEquals("http.parameter:q", seen.get(1).getAttributes().get("source"));
      assertEquals(sql, seen.get(1).getAttributes().get("argument"));
      System.out.println(
          "IAST metric sink=command-execution count=1 start="
              + seen.get(0).getAttributes().get("start")
              + " end="
              + seen.get(0).getAttributes().get("end"));
      System.out.println(
          "IAST metric sink=sql count=1 start="
              + seen.get(1).getAttributes().get("start")
              + " end="
              + seen.get(1).getAttributes().get("end"));
    } finally {
      InterceptorEventPublisher.getInstance().removeListener(exporter);
      InterceptorEventPublisher.getInstance().removeListener(collector);
    }
  }
}
