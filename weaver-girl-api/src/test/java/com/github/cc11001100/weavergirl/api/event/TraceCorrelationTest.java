package com.github.cc11001100.weavergirl.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.cc11001100.weavergirl.api.context.ContextPropagators;
import com.github.cc11001100.weavergirl.api.context.ContextSnapshot;
import com.github.cc11001100.weavergirl.api.taint.CommandExecutionSink;
import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintFindings;
import com.github.cc11001100.weavergirl.api.taint.TaintPropagation;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Public publish path stamps the span that is current on the calling thread. */
class TraceCorrelationTest {

  private final List<InterceptorEvent> events = new ArrayList<InterceptorEvent>();
  private InterceptorEventListener listener;

  @BeforeEach
  void setUp() {
    Tracer.clearCurrentSpan();
    listener =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            events.add(event);
          }
        };
    InterceptorEventPublisher.getInstance().addListener(listener);
    events.clear();
  }

  @AfterEach
  void tearDown() {
    InterceptorEventPublisher.getInstance().removeListener(listener);
    Tracer.clearCurrentSpan();
    for (int i = 0; i < 4; i++) {
      Taint.closeScope();
    }
  }

  @Test
  void noSpanOmitsTraceIdsAndReplacementChangesTheNextEvent() {
    InterceptorEventPublisher.getInstance()
        .publish(InterceptorEvent.builder().type("plain").plugin("test").build());
    Map<String, String> absent = events.get(0).getAttributes();
    assertNull(absent.get(InterceptorEvent.TRACE_ID));
    assertNull(absent.get(InterceptorEvent.SPAN_ID));

    SpanContext first = Tracer.startSpan();
    InterceptorEventPublisher.getInstance()
        .publish(InterceptorEvent.builder().type("plain").plugin("test").build());
    assertEquals(first.getTraceId(), events.get(1).getAttributes().get(InterceptorEvent.TRACE_ID));
    assertEquals(first.getSpanId(), events.get(1).getAttributes().get(InterceptorEvent.SPAN_ID));

    SpanContext second = Tracer.startSpan();
    InterceptorEventPublisher.getInstance()
        .publish(InterceptorEvent.builder().type("plain").plugin("test").build());
    assertEquals(second.getTraceId(), events.get(2).getAttributes().get(InterceptorEvent.TRACE_ID));
    assertEquals(second.getSpanId(), events.get(2).getAttributes().get(InterceptorEvent.SPAN_ID));

    Tracer.endSpan("obs", "OK");
    InterceptorEventPublisher.getInstance()
        .publish(InterceptorEvent.builder().type("plain").plugin("test").build());
    assertNull(events.get(3).getAttributes().get(InterceptorEvent.TRACE_ID));
    assertNull(events.get(3).getAttributes().get(InterceptorEvent.SPAN_ID));
    System.out.println(
        "IAST trace absent-then-present traceId="
            + first.getTraceId()
            + " spanId="
            + first.getSpanId()
            + " replacedTraceId="
            + second.getTraceId()
            + " replacedSpanId="
            + second.getSpanId());
  }

  @Test
  void sinkFindingCarriesSliceAndActiveSpan() {
    Taint.openScope();
    SpanContext span = Tracer.startSpan();
    String dirty = new String("DIRTY");
    Taint.tag(dirty, "http.parameter:q");
    String sql = "SELECT ".concat(dirty);
    TaintPropagation.propagateConcat(sql, "SELECT ", dirty);
    CommandExecutionSink.observe("clean");
    CommandExecutionSink.observe(sql);

    assertEquals(1, findings().size());
    Map<String, String> detail = findings().get(0).getAttributes();
    assertEquals("http.parameter:q", detail.get("source"));
    assertEquals(Taint.SINK_COMMAND, detail.get("sink"));
    assertEquals(sql, detail.get("argument"));
    assertEquals(Integer.toString("SELECT ".length()), detail.get("start"));
    assertEquals(Integer.toString(sql.length()), detail.get("end"));
    assertEquals(span.getTraceId(), detail.get(InterceptorEvent.TRACE_ID));
    assertEquals(span.getSpanId(), detail.get(InterceptorEvent.SPAN_ID));
    System.out.println(
        "IAST trace source="
            + detail.get("source")
            + " sink="
            + detail.get("sink")
            + " start="
            + detail.get("start")
            + " end="
            + detail.get("end")
            + " traceId="
            + detail.get(InterceptorEvent.TRACE_ID)
            + " spanId="
            + detail.get(InterceptorEvent.SPAN_ID));
  }

  @Test
  void workerPublishCarriesRestoredSpan() throws Exception {
    final String value = new String("from-parent");
    Taint.openScope();
    Taint.tag(value, "http.parameter:cmd");
    final SpanContext span = Tracer.startSpan();
    ContextSnapshot snapshot = ContextPropagators.capture();
    Tracer.clearCurrentSpan();
    Taint.closeScope();

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(
              ContextPropagators.wrap(
                  new Runnable() {
                    @Override
                    public void run() {
                      CommandExecutionSink.observe(value);
                    }
                  },
                  snapshot))
          .get(5, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, findings().size());
    Map<String, String> detail = findings().get(0).getAttributes();
    assertEquals(span.getTraceId(), detail.get(InterceptorEvent.TRACE_ID));
    assertEquals(span.getSpanId(), detail.get(InterceptorEvent.SPAN_ID));
    assertEquals("http.parameter:cmd", detail.get("source"));
    assertEquals(Taint.SINK_COMMAND, detail.get("sink"));
    System.out.println(
        "IAST trace worker traceId="
            + detail.get(InterceptorEvent.TRACE_ID)
            + " spanId="
            + detail.get(InterceptorEvent.SPAN_ID)
            + " sink="
            + detail.get("sink"));
  }

  private List<InterceptorEvent> findings() {
    List<InterceptorEvent> findings = new ArrayList<InterceptorEvent>();
    for (InterceptorEvent event : events) {
      if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
        findings.add(event);
      }
    }
    return findings;
  }
}
