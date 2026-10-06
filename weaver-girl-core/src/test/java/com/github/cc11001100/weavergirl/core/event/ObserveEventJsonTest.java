package com.github.cc11001100.weavergirl.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.cc11001100.weavergirl.api.context.ContextPropagators;
import com.github.cc11001100.weavergirl.api.context.ContextSnapshot;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.taint.CommandExecutionSink;
import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintFindings;
import com.github.cc11001100.weavergirl.api.taint.TaintPropagation;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.management.AgentApiServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Structured JSON and the live /events route expose the same slice finding. */
class ObserveEventJsonTest {

  private final List<InterceptorEvent> findings = new ArrayList<InterceptorEvent>();
  private InterceptorEventListener listener;

  @BeforeEach
  void setUp() {
    Tracer.clearCurrentSpan();
    InterceptorEventPublisher.getInstance().clearHistory();
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
  }

  @AfterEach
  void tearDown() {
    InterceptorEventPublisher.getInstance().removeListener(listener);
    InterceptorEventPublisher.getInstance().clearHistory();
    Tracer.clearCurrentSpan();
    for (int i = 0; i < 4; i++) {
      Taint.closeScope();
    }
  }

  @Test
  void findingJsonHasSliceAndSpanAndOmitsIdsWhenNoSpan() throws Exception {
    Taint.openScope();
    String dirty = new String("DIRTY");
    Taint.tag(dirty, "http.parameter:q");
    String sql = "SELECT ".concat(dirty);
    TaintPropagation.propagateConcat(sql, "SELECT ", dirty);
    CommandExecutionSink.observe("clean-only");
    assertEquals(0, findings.size(), "clean argument produces no finding");

    CommandExecutionSink.observe(sql);
    assertEquals(1, findings.size());
    String noSpan = new JsonEventListener().toJson(findings.get(0));
    Map<String, Object> parsed = StrictJson.object(noSpan);
    Map<String, Object> attrs = attributes(parsed);
    assertEquals("http.parameter:q", attrs.get("source"));
    assertEquals(Taint.SINK_COMMAND, attrs.get("sink"));
    assertEquals("7", attrs.get("start"));
    assertEquals("12", attrs.get("end"));
    assertEquals(sql, attrs.get("argument"));
    assertNull(attrs.get(InterceptorEvent.TRACE_ID));
    assertNull(attrs.get(InterceptorEvent.SPAN_ID));

    findings.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
    SpanContext span = Tracer.startSpan();
    CommandExecutionSink.observe(sql);
    String withSpan = JsonEventListener.render(findings.get(0));
    Map<String, Object> spanned = attributes(StrictJson.object(withSpan));
    assertEquals(span.getTraceId(), spanned.get(InterceptorEvent.TRACE_ID));
    assertEquals(span.getSpanId(), spanned.get(InterceptorEvent.SPAN_ID));
    assertEquals("http.parameter:q", spanned.get("source"));
    assertEquals(Taint.SINK_COMMAND, spanned.get("sink"));
    assertEquals("7", spanned.get("start"));
    assertEquals("12", spanned.get("end"));
    System.out.println(
        "IAST json source="
            + spanned.get("source")
            + " sink="
            + spanned.get("sink")
            + " start="
            + spanned.get("start")
            + " end="
            + spanned.get("end")
            + " traceId="
            + spanned.get("traceId")
            + " spanId="
            + spanned.get("spanId"));
    System.out.println("IAST json-body " + withSpan);
  }

  @Test
  void workerJsonCarriesRestoredSpan() throws Exception {
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
    assertEquals(1, findings.size());
    Map<String, Object> attrs = attributes(StrictJson.object(JsonEventListener.render(findings.get(0))));
    assertEquals(span.getTraceId(), attrs.get("traceId"));
    assertEquals(span.getSpanId(), attrs.get("spanId"));
    assertEquals("http.parameter:cmd", attrs.get("source"));
    assertEquals(Taint.SINK_COMMAND, attrs.get("sink"));
    System.out.println(
        "IAST json worker traceId="
            + attrs.get("traceId")
            + " spanId="
            + attrs.get("spanId")
            + " sink="
            + attrs.get("sink"));
  }

  @Test
  void eventsRouteReturnsTheRecordedFinding() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    AgentApiServer server = new AgentApiServer(port);
    server.start();
    try {
      Taint.openScope();
      SpanContext span = Tracer.startSpan();
      String arg = new String("listed");
      Taint.tag(arg, "http.parameter:cmd");
      CommandExecutionSink.observe(arg);
      String body = httpGet(port, "/events");
      Map<String, Object> root = StrictJson.object(body);
      List<Object> events = StrictJson.array(root.get("events"));
      int findingsOnRoute = 0;
      Map<String, Object> match = null;
      for (Object item : events) {
        Map<String, Object> event = StrictJson.objectValue(item);
        if (!"iast-finding".equals(event.get("type"))) {
          continue;
        }
        findingsOnRoute++;
        match = attributes(event);
      }
      assertEquals(1, findingsOnRoute);
      assertEquals("http.parameter:cmd", match.get("source"));
      assertEquals(Taint.SINK_COMMAND, match.get("sink"));
      assertEquals("0", match.get("start"));
      assertEquals(Integer.toString(arg.length()), match.get("end"));
      assertEquals(arg, match.get("argument"));
      assertEquals(span.getTraceId(), match.get("traceId"));
      assertEquals(span.getSpanId(), match.get("spanId"));
      System.out.println("IAST events-route " + body);

      findings.clear();
      InterceptorEventPublisher.getInstance().clearHistory();
      Tracer.endSpan("observe", "OK");
      String plain = new String("nospan");
      Taint.tag(plain, "http.parameter:q");
      CommandExecutionSink.observe(plain);
      String bare = httpGet(port, "/events");
      Map<String, Object> bareAttrs = null;
      for (Object item : StrictJson.array(StrictJson.object(bare).get("events"))) {
        Map<String, Object> event = StrictJson.objectValue(item);
        if ("iast-finding".equals(event.get("type"))) {
          bareAttrs = attributes(event);
        }
      }
      assertEquals("http.parameter:q", bareAttrs.get("source"));
      assertNull(bareAttrs.get("traceId"));
      assertNull(bareAttrs.get("spanId"));
      System.out.println(
          "IAST events-route no-span source="
              + bareAttrs.get("source")
              + " sink="
              + bareAttrs.get("sink")
              + " start="
              + bareAttrs.get("start")
              + " end="
              + bareAttrs.get("end"));
    } finally {
      server.stop();
    }
  }

  private static Map<String, Object> attributes(Map<String, Object> event) {
    return StrictJson.objectValue(event.get("attributes"));
  }

  private static String httpGet(int port, String path) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    connection.setRequestMethod("GET");
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
    StringBuilder body = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      body.append(line);
    }
    reader.close();
    return body.toString();
  }

  /** Strict JSON reader for the documents this module emits. Rejects trailing commas. */
  static final class StrictJson {
    private final String text;
    private int index;

    private StrictJson(String text) {
      this.text = text;
    }

    static Map<String, Object> object(String text) {
      StrictJson parser = new StrictJson(text);
      Map<String, Object> value = parser.parseObject();
      parser.skip();
      if (parser.index != parser.text.length()) {
        throw new IllegalArgumentException("trailing input at " + parser.index);
      }
      return value;
    }

    static Map<String, Object> objectValue(Object value) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) value;
      return map;
    }

    static List<Object> array(Object value) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) value;
      return list;
    }

    private Map<String, Object> parseObject() {
      skip();
      expect('{');
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      skip();
      if (peek('}')) {
        index++;
        return map;
      }
      while (true) {
        skip();
        String key = parseString();
        skip();
        expect(':');
        map.put(key, parseValue());
        skip();
        if (peek('}')) {
          index++;
          return map;
        }
        expect(',');
        skip();
        if (peek('}')) {
          throw new IllegalArgumentException("trailing comma");
        }
      }
    }

    private List<Object> parseArray() {
      expect('[');
      List<Object> list = new ArrayList<Object>();
      skip();
      if (peek(']')) {
        index++;
        return list;
      }
      while (true) {
        list.add(parseValue());
        skip();
        if (peek(']')) {
          index++;
          return list;
        }
        expect(',');
        skip();
        if (peek(']')) {
          throw new IllegalArgumentException("trailing comma");
        }
      }
    }

    private Object parseValue() {
      skip();
      char c = text.charAt(index);
      if (c == '{') {
        return parseObject();
      }
      if (c == '[') {
        return parseArray();
      }
      if (c == '"') {
        return parseString();
      }
      if (text.startsWith("null", index)) {
        index += 4;
        return null;
      }
      if (text.startsWith("true", index)) {
        index += 4;
        return Boolean.TRUE;
      }
      if (text.startsWith("false", index)) {
        index += 5;
        return Boolean.FALSE;
      }
      int start = index;
      if (c == '-') {
        index++;
      }
      while (index < text.length() && Character.isDigit(text.charAt(index))) {
        index++;
      }
      if (start == index || (text.charAt(start) == '-' && index == start + 1)) {
        throw new IllegalArgumentException("bad number at " + start);
      }
      return Long.valueOf(text.substring(start, index));
    }

    private String parseString() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (index < text.length()) {
        char c = text.charAt(index++);
        if (c == '"') {
          return out.toString();
        }
        if (c == '\\') {
          char esc = text.charAt(index++);
          if (esc == '"' || esc == '\\' || esc == '/') {
            out.append(esc);
          } else if (esc == 'n') {
            out.append('\n');
          } else if (esc == 'r') {
            out.append('\r');
          } else if (esc == 't') {
            out.append('\t');
          } else if (esc == 'u') {
            int code = Integer.parseInt(text.substring(index, index + 4), 16);
            index += 4;
            out.append((char) code);
          } else {
            throw new IllegalArgumentException("bad escape " + esc);
          }
        } else {
          out.append(c);
        }
      }
      throw new IllegalArgumentException("unterminated string");
    }

    private void skip() {
      while (index < text.length() && text.charAt(index) <= ' ') {
        index++;
      }
    }

    private void expect(char c) {
      skip();
      if (index >= text.length() || text.charAt(index) != c) {
        throw new IllegalArgumentException("expected " + c + " at " + index);
      }
      index++;
    }

    private boolean peek(char c) {
      return index < text.length() && text.charAt(index) == c;
    }
  }
}
