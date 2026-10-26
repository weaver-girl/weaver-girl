package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Command and SQL sink hooks, delivered only through the public event and exporter APIs. */
class TaintSinkTest {

  @BeforeEach
  void open() {
    Taint.openScope();
    InterceptorEventPublisher.getInstance().clearHistory();
  }

  @AfterEach
  void close() {
    Taint.closeScope();
  }

  @Test
  void commandSinkEmitsOneFindingForTaintedArgumentAndNoneForClean() {
    final List<InterceptorEvent> findings = new ArrayList<InterceptorEvent>();
    InterceptorEventListener listener =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
              findings.add(event);
            }
          }
        };
    InterceptorEventPublisher.getInstance().addListener(listener);
    try {
      String tainted = new String("rm-rf");
      Taint.tag(tainted, "http.parameter:cmd");
      CommandExecutionSink.observe(tainted);
      CommandExecutionSink.observe("echo");

      assertEquals(1, findings.size());
      Map<String, String> attributes = findings.get(0).getAttributes();
      assertEquals("http.parameter:cmd", attributes.get("source"));
      assertEquals(Taint.SINK_COMMAND, attributes.get("sink"));
      assertEquals(tainted, attributes.get("argument"));
      System.out.println(
          "IAST finding source="
              + attributes.get("source")
              + " sink="
              + attributes.get("sink")
              + " argument="
              + attributes.get("argument"));
    } finally {
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  @Test
  void sqlSinkEmitsOneFindingForTaintedArgumentAndNoneForClean() {
    final List<InterceptorEvent> findings = new ArrayList<InterceptorEvent>();
    InterceptorEventListener listener =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
              findings.add(event);
            }
          }
        };
    InterceptorEventPublisher.getInstance().addListener(listener);
    try {
      String tainted = new String("SELECT secret");
      Taint.tag(tainted, "http.parameter:q");
      SqlExecutionSink.observe(tainted);
      SqlExecutionSink.observe("SELECT 1");

      assertEquals(1, findings.size());
      Map<String, String> attributes = findings.get(0).getAttributes();
      assertEquals("http.parameter:q", attributes.get("source"));
      assertEquals(Taint.SINK_SQL, attributes.get("sink"));
      assertEquals(tainted, attributes.get("argument"));
      System.out.println(
          "IAST finding source="
              + attributes.get("source")
              + " sink="
              + attributes.get("sink")
              + " argument="
              + attributes.get("argument"));
    } finally {
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  @Test
  void exporterReceivesFindingWithoutReadingAgentInternals() {
    final List<InterceptorEvent> exported = new ArrayList<InterceptorEvent>();
    DataExporter exporter =
        new DataExporter() {
          @Override
          public String name() {
            return "iast-sink-test";
          }

          @Override
          public void export(InterceptorEvent event) {
            if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
              exported.add(event);
            }
          }
        };
    ExporterRegistry.register(exporter);
    ExporterRegistry.activate(exporter.name());
    try {
      String tainted = new String("id-9");
      Taint.tag(tainted, "http.header:X-User");
      List<String> command = Collections.singletonList(tainted);
      CommandExecutionSink.observe(command);

      assertEquals(1, exported.size());
      Map<String, String> attributes = exported.get(0).getAttributes();
      assertEquals("http.header:X-User", attributes.get("source"));
      assertEquals(Taint.SINK_COMMAND, attributes.get("sink"));
      assertEquals(tainted, attributes.get("argument"));
      assertTrue(attributes.containsKey("argument"));
      System.out.println(
          "IAST exporter source="
              + attributes.get("source")
              + " sink="
              + attributes.get("sink")
              + " argument="
              + attributes.get("argument"));
    } finally {
      ExporterRegistry.deactivate(exporter.name());
      ExporterRegistry.unregister(exporter.name());
    }
  }

  @Test
  void commandObservationWalksArraysListsAndIgnoresTheRest() {
    final List<InterceptorEvent> findings = new ArrayList<InterceptorEvent>();
    InterceptorEventListener listener =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
              findings.add(event);
            }
          }
        };
    InterceptorEventPublisher.getInstance().addListener(listener);
    try {
      String tainted = new String("rm-rf");
      Taint.tag(tainted, "http.parameter:cmd");

      TaintPropagation.observeCommandList(null);
      TaintPropagation.observeCommandValue(Integer.valueOf(1));
      TaintPropagation.observeCommandValue(new String[] {tainted, "echo"});
      TaintPropagation.observeCommandList(Arrays.asList(tainted, Integer.valueOf(2)));

      assertEquals(2, findings.size());
    } finally {
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  @Test
  void observersAreNotifiedPerSliceAndAThrowingOneDoesNotStopTheNext() {
    TaintBridge.SinkObserver broken =
        new TaintBridge.SinkObserver() {
          @Override
          public void onFinding(
              String sourceLabel, String sinkKind, String argument, int start, int end) {
            throw new IllegalStateException("subscriber failed");
          }
        };
    final List<String> seen = new ArrayList<String>();
    TaintBridge.SinkObserver recording =
        new TaintBridge.SinkObserver() {
          @Override
          public void onFinding(
              String sourceLabel, String sinkKind, String argument, int start, int end) {
            seen.add(sourceLabel + ":" + sinkKind);
          }
        };
    TaintBridge.addObserver(null);
    TaintBridge.addObserver(broken);
    TaintBridge.addObserver(recording);
    try {
      String tainted = new String("rm-rf");
      Taint.tag(tainted, "http.parameter:cmd");
      Taint.tag(tainted, "http.parameter:cmd");

      TaintPropagation.observeCommand(tainted);
      TaintPropagation.observeSql(null);
      TaintPropagation.observeSql("select 1");

      assertEquals(1, seen.size());
      assertEquals("http.parameter:cmd:command-execution", seen.get(0));
    } finally {
      TaintBridge.removeObserver(broken);
      TaintBridge.removeObserver(recording);
    }
  }

  @Test
  void reentrancyGuardRejectsNestedEntryAndExecFramesNest() {
    assertTrue(TaintBridge.tryEnter());
    assertFalse(TaintBridge.tryEnter(), "a nested entry must be refused");
    TaintBridge.leave();
    assertTrue(TaintBridge.tryEnter(), "leaving releases the guard");
    TaintBridge.leave();

    assertTrue(TaintBridge.beginExecObservation());
    assertFalse(TaintBridge.beginExecObservation(), "a nested exec stays inside the outer frame");
    TaintBridge.endExecObservation();
    assertFalse(TaintBridge.tryEnter(), "the outer frame still holds the guard");
    TaintBridge.endExecObservation();
    assertTrue(TaintBridge.tryEnter(), "ending the owning frame releases the guard");
    TaintBridge.leave();

    TaintBridge.endExecObservation();
    TaintBridge.leave();
  }
}
