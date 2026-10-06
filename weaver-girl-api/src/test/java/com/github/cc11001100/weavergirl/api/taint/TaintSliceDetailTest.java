package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sink subscribers read one detail per tainted slice: source, half-open interval, sink kind, and
 * the argument text. Propagation goes through {@link TaintPropagation}.
 */
class TaintSliceDetailTest {

  private final List<InterceptorEvent> findings = new ArrayList<InterceptorEvent>();
  private InterceptorEventListener listener;

  @BeforeEach
  void open() {
    Taint.openScope();
    findings.clear();
    listener =
        new InterceptorEventListener() {
          @Override
          public void onEvent(InterceptorEvent event) {
            if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
              findings.add(event);
            }
          }
        };
    InterceptorEventPublisher.getInstance().addListener(listener);
    InterceptorEventPublisher.getInstance().clearHistory();
  }

  @AfterEach
  void close() {
    InterceptorEventPublisher.getInstance().removeListener(listener);
    Taint.closeScope();
  }

  @Test
  void concatKeepsCleanEdgesAndDistinguishesTwoSources() {
    String prefix = "pre:";
    String left = "AAA";
    String right = "BBB";
    String suffix = ":suf";
    Taint.tag(left, "source-left");
    Taint.tag(right, "source-right");
    String combined = prefix + left + right + suffix;
    TaintPropagation.propagateConcat(combined, prefix, left, right, suffix);

    CommandExecutionSink.observe(combined);
    CommandExecutionSink.observe("clean-arg");

    assertEquals(2, findings.size());
    Map<String, String> first = findings.get(0).getAttributes();
    Map<String, String> second = findings.get(1).getAttributes();
    assertEquals("source-left", first.get("source"));
    assertEquals(Taint.SINK_COMMAND, first.get("sink"));
    assertEquals(combined, first.get("argument"));
    assertEquals(Integer.toString(prefix.length()), first.get("start"));
    assertEquals(Integer.toString(prefix.length() + left.length()), first.get("end"));
    assertEquals("source-right", second.get("source"));
    assertEquals(Taint.SINK_COMMAND, second.get("sink"));
    assertEquals(combined, second.get("argument"));
    assertEquals(Integer.toString(prefix.length() + left.length()), second.get("start"));
    assertEquals(
        Integer.toString(prefix.length() + left.length() + right.length()), second.get("end"));
    assertTrue(Integer.parseInt(first.get("start")) > 0);
    assertTrue(Integer.parseInt(second.get("end")) < combined.length());
    System.out.println(
        "IAST detail source="
            + first.get("source")
            + " sink="
            + first.get("sink")
            + " start="
            + first.get("start")
            + " end="
            + first.get("end"));
    System.out.println(
        "IAST detail source="
            + second.get("source")
            + " sink="
            + second.get("sink")
            + " start="
            + second.get("start")
            + " end="
            + second.get("end"));
  }

  @Test
  void substringDetailCoversOnlyTheOverlappingSlice() {
    String clean = "CLEAN";
    String dirty = "DIRTY";
    String tail = "TAIL";
    Taint.tag(dirty, "origin");
    String all = clean + dirty + tail;
    TaintPropagation.propagateConcat(all, clean, dirty, tail);
    int begin = 3;
    int end = 8;
    String sub = all.substring(begin, end);
    TaintPropagation.propagateSubstring(sub, all, begin, end);

    SqlExecutionSink.observe(sub);

    assertEquals(1, findings.size());
    Map<String, String> detail = findings.get(0).getAttributes();
    assertEquals("origin", detail.get("source"));
    assertEquals(Taint.SINK_SQL, detail.get("sink"));
    assertEquals(sub, detail.get("argument"));
    assertEquals("2", detail.get("start"));
    assertEquals("5", detail.get("end"));
    System.out.println(
        "IAST detail source="
            + detail.get("source")
            + " sink="
            + detail.get("sink")
            + " start="
            + detail.get("start")
            + " end="
            + detail.get("end"));
  }

  @Test
  void stringBuilderDetailAndExporterSeeTheSameSlice() {
    String prefix = "pre:";
    String dirty = "IN";
    String suffix = ":post";
    Taint.tag(dirty, "builder-origin");
    StringBuilder builder = new StringBuilder();
    builder.append(prefix);
    TaintPropagation.propagateAppend(builder, prefix, 0);
    int offset = builder.length();
    builder.append(dirty);
    TaintPropagation.propagateAppend(builder, dirty, offset);
    builder.append(suffix);
    TaintPropagation.propagateAppend(builder, suffix, offset + dirty.length());
    String result = builder.toString();
    TaintPropagation.propagateToString(result, builder);

    final List<InterceptorEvent> exported = new ArrayList<InterceptorEvent>();
    DataExporter exporter =
        new DataExporter() {
          @Override
          public String name() {
            return "iast-slice-test";
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
      CommandExecutionSink.observe(result);
      assertEquals(1, exported.size());
      Map<String, String> detail = exported.get(0).getAttributes();
      assertEquals("builder-origin", detail.get("source"));
      assertEquals(Taint.SINK_COMMAND, detail.get("sink"));
      assertEquals(result, detail.get("argument"));
      assertEquals(Integer.toString(prefix.length()), detail.get("start"));
      assertEquals(Integer.toString(prefix.length() + dirty.length()), detail.get("end"));
      assertTrue(Integer.parseInt(detail.get("end")) < result.length());
      System.out.println(
          "IAST detail source="
              + detail.get("source")
              + " sink="
              + detail.get("sink")
              + " start="
              + detail.get("start")
              + " end="
              + detail.get("end"));
    } finally {
      ExporterRegistry.deactivate(exporter.name());
      ExporterRegistry.unregister(exporter.name());
    }
  }
}
