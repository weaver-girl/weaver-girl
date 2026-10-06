package com.github.cc11001100.weavergirl.plugins.iast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintBridge;
import com.github.cc11001100.weavergirl.api.taint.TaintFindings;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.management.AgentApiServer;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import com.github.cc11001100.weavergirl.plugins.servlet.ServletPlugin;
import iastlaunch.IastTargetServlet;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Two launches of the real self-attach path: HTTP parameter capture, {@code String.concat}, and
 * {@code ProcessBuilder.start} advice. A clean command in the same JVM produces no finding.
 */
class IastHookLaunchTest {

  private static Instrumentation instrumentation;
  private static IastTargetServlet servlet;
  private static AgentApiServer eventServer;
  private static int eventPort;
  private static final List<InterceptorEvent> FINDINGS = new ArrayList<InterceptorEvent>();
  private static final InterceptorEventListener LISTENER =
      new InterceptorEventListener() {
        @Override
        public void onEvent(InterceptorEvent event) {
          if (TaintFindings.EVENT_TYPE.equals(event.getType())) {
            FINDINGS.add(event);
          }
        }
      };

  @BeforeAll
  static void install() throws Exception {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("IAST launch self-attach failed: " + failure);
      failure.printStackTrace(System.out);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "ByteBuddy self-attach unavailable");
    TaintInstrumentation.install(instrumentation);
    if (!TaintInstrumentation.isJdkInstrumented()) {
      Throwable failure = TaintInstrumentation.installFailure();
      System.out.println("IAST launch JDK advice failed: " + failure);
      if (failure != null) {
        failure.printStackTrace(System.out);
      }
    }
    assertTrue(
        TaintInstrumentation.isJdkInstrumented(),
        "JDK taint advice must share the bootstrap TaintBridge");

    TaintBridge.suppressProcessStart = true;
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    ServletPlugin plugin = new ServletPlugin();
    plugin.init(
        new PluginContext() {
          @Override
          public InterceptorRegistry getRegistry() {
            return null;
          }

          @Override
          public String getConfig(String key) {
            return null;
          }

          @Override
          public String getConfig(String key, String defaultValue) {
            return defaultValue;
          }

          @Override
          public Map<String, String> getAllConfig() {
            return Collections.emptyMap();
          }

          @Override
          public String getPluginName() {
            return "servlet";
          }
        });
    plugin.registerInterceptors(registry);
    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(true);
    transformer.install(instrumentation);
    transformer.retransformLoadedClasses();
    servlet = new IastTargetServlet();
    InterceptorEventPublisher.getInstance().addListener(LISTENER);
    try (ServerSocket socket = new ServerSocket(0)) {
      eventPort = socket.getLocalPort();
    }
    eventServer = new AgentApiServer(eventPort);
    eventServer.start();
  }

  @AfterAll
  static void tearDown() {
    if (eventServer != null) {
      eventServer.stop();
    }
    TaintBridge.suppressProcessStart = false;
    InterceptorEventPublisher.getInstance().removeListener(LISTENER);
    InterceptorHolder.setRegistry(null);
    for (int i = 0; i < 8; i++) {
      Taint.closeScope();
    }
  }

  @Test
  void twoLaunchesReportTheSameParameterFindingAndACleanArgumentDoesNot() throws Exception {
    String payload = new String("payload");
    Map<String, String> first = runOnce(payload);
    Map<String, String> second = runOnce(payload);
    assertEquals(first.get("source"), second.get("source"));
    assertEquals(first.get("sink"), second.get("sink"));
    assertEquals(first.get("argument"), second.get("argument"));
    assertEquals(first.get("start"), second.get("start"));
    assertEquals(first.get("end"), second.get("end"));
    assertEquals(Taint.PARAMETER_PREFIX + "cmd", first.get("source"));
    assertEquals(Taint.SINK_COMMAND, first.get("sink"));
    assertEquals("cmd:" + payload, first.get("argument"));
    assertEquals(Integer.toString("cmd:".length()), first.get("start"));
    assertEquals(Integer.toString("cmd:".length() + payload.length()), first.get("end"));
    assertEquals(first.get("traceId"), first.get("expectedTraceId"));
    assertEquals(second.get("traceId"), second.get("expectedTraceId"));
    assertEquals(first.get("traceId"), first.get("listingTraceId"));
    assertEquals(second.get("traceId"), second.get("listingTraceId"));

    FINDINGS.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
    Taint.openScope();
    try {
      new ProcessBuilder("echo-clean").start();
    } finally {
      Taint.closeScope();
    }
    assertEquals(0, FINDINGS.size(), "clean command argument produces no finding");
    String listing = httpGet("/events");
    assertEquals(0, count(listing, "\"type\":\"iast-finding\""));
    System.out.println("IAST launch clean-argument findings=0 listing=" + listing);
  }

  @Test
  void nestedExecAndProcessStartEmitOneSliceWhileTheBodyRuns() throws Exception {
    TaintBridge.suppressProcessStart = false;
    String param = new String("payload");
    try {
      for (int i = 0; i < 8; i++) {
        Taint.closeScope();
      }
      Taint.openScope();
      Taint.tag(param, Taint.PARAMETER_PREFIX + "cmd");

      String arrayArg = "echo:".concat(param);
      FINDINGS.clear();
      Process arrayProcess = Runtime.getRuntime().exec(new String[] {"/bin/true", arrayArg});
      assertEquals(0, finish(arrayProcess));
      assertEquals(1, FINDINGS.size(), "nested Runtime.exec overloads must emit the slice once");
      Map<String, String> arrayDetail = FINDINGS.get(0).getAttributes();
      assertEquals(Taint.PARAMETER_PREFIX + "cmd", arrayDetail.get("source"));
      assertEquals(Taint.SINK_COMMAND, arrayDetail.get("sink"));
      assertEquals(arrayArg, arrayDetail.get("argument"));
      assertEquals(Integer.toString("echo:".length()), arrayDetail.get("start"));
      assertEquals(Integer.toString(arrayArg.length()), arrayDetail.get("end"));
      System.out.println(
          "IAST exec-body source="
              + arrayDetail.get("source")
              + " sink="
              + arrayDetail.get("sink")
              + " start="
              + arrayDetail.get("start")
              + " end="
              + arrayDetail.get("end")
              + " argument="
              + arrayDetail.get("argument"));

      String command = "/bin/true ".concat(param);
      FINDINGS.clear();
      Process stringProcess = Runtime.getRuntime().exec(command);
      assertEquals(0, finish(stringProcess));
      assertEquals(1, FINDINGS.size(), "Runtime.exec(String) delegates must emit the slice once");
      Map<String, String> stringDetail = FINDINGS.get(0).getAttributes();
      assertEquals(Taint.PARAMETER_PREFIX + "cmd", stringDetail.get("source"));
      assertEquals(Taint.SINK_COMMAND, stringDetail.get("sink"));
      assertEquals(command, stringDetail.get("argument"));
      assertEquals(Integer.toString("/bin/true ".length()), stringDetail.get("start"));
      assertEquals(Integer.toString(command.length()), stringDetail.get("end"));
      System.out.println(
          "IAST exec-body source="
              + stringDetail.get("source")
              + " sink="
              + stringDetail.get("sink")
              + " start="
              + stringDetail.get("start")
              + " end="
              + stringDetail.get("end")
              + " argument="
              + stringDetail.get("argument"));

      String builderArg = "echo:".concat(param);
      FINDINGS.clear();
      Process started = new ProcessBuilder("/bin/true", builderArg).start();
      assertEquals(0, finish(started));
      assertEquals(1, FINDINGS.size(), "ProcessBuilder.start must not duplicate a nested exec slice");
      Map<String, String> startDetail = FINDINGS.get(0).getAttributes();
      assertEquals(Taint.PARAMETER_PREFIX + "cmd", startDetail.get("source"));
      assertEquals(Taint.SINK_COMMAND, startDetail.get("sink"));
      assertEquals(builderArg, startDetail.get("argument"));
      assertEquals(Integer.toString("echo:".length()), startDetail.get("start"));
      assertEquals(Integer.toString(builderArg.length()), startDetail.get("end"));
      System.out.println(
          "IAST exec-body source="
              + startDetail.get("source")
              + " sink="
              + startDetail.get("sink")
              + " start="
              + startDetail.get("start")
              + " end="
              + startDetail.get("end")
              + " argument="
              + startDetail.get("argument"));
    } finally {
      Taint.closeScope();
      TaintBridge.suppressProcessStart = true;
    }
  }

  private static int finish(Process process) throws Exception {
    try {
      process.getOutputStream().close();
      process.getInputStream().close();
      process.getErrorStream().close();
      return process.waitFor();
    } finally {
      process.destroy();
    }
  }

  private static Map<String, String> runOnce(String payload) throws Exception {
    for (int i = 0; i < 8; i++) {
      Taint.closeScope();
    }
    FINDINGS.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
    SpanContext span = Tracer.startSpan();
    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
    Map<String, String[]> parameters = new HashMap<String, String[]>();
    parameters.put("cmd", new String[] {payload});
    final Map<String, String> headers = new HashMap<String, String>();
    Tracer.inject(span, headers);
    Mockito.when(request.getParameterMap()).thenReturn(parameters);
    Mockito.when(request.getParameter("cmd")).thenReturn(payload);
    Mockito.when(request.getHeaderNames())
        .thenAnswer(
            invocation -> Collections.enumeration(new ArrayList<String>(headers.keySet())));
    Mockito.when(request.getHeader(Mockito.anyString()))
        .thenAnswer(
            invocation -> headers.get(invocation.getArgument(0)));
    Mockito.when(request.getMethod()).thenReturn("GET");
    Mockito.when(request.getRequestURI()).thenReturn("/cmd");

    try {
      servlet.service(request, response);
    } finally {
      if (Tracer.getCurrentSpan() != null) {
        Tracer.endSpan("iast-launch", "OK");
      }
    }

    assertEquals(1, FINDINGS.size(), "tainted derived command must produce one finding");
    Map<String, String> attributes = new HashMap<String, String>(FINDINGS.get(0).getAttributes());
    attributes.put("expectedTraceId", span.getTraceId());
    String listing = httpGet("/events");
    assertEquals(1, count(listing, "\"type\":\"iast-finding\""));
    assertTrue(listing.contains("\"source\":\"" + Taint.PARAMETER_PREFIX + "cmd\""));
    assertTrue(listing.contains("\"sink\":\"" + Taint.SINK_COMMAND + "\""));
    assertTrue(listing.contains("\"start\":\"" + attributes.get("start") + "\""));
    assertTrue(listing.contains("\"end\":\"" + attributes.get("end") + "\""));
    assertTrue(listing.contains("\"traceId\":\"" + span.getTraceId() + "\""));
    assertTrue(listing.contains("\"spanId\":\"" + span.getSpanId() + "\""));
    attributes.put("listingTraceId", span.getTraceId());
    System.out.println("IAST launch listing " + listing);
    System.out.println(
        "IAST launch source="
            + attributes.get("source")
            + " sink="
            + attributes.get("sink")
            + " start="
            + attributes.get("start")
            + " end="
            + attributes.get("end")
            + " traceId="
            + attributes.get("traceId")
            + " spanId="
            + attributes.get("spanId")
            + " argument="
            + attributes.get("argument"));
    assertEquals(payload, IastTargetServlet.lastDerived.substring("cmd:".length()));
    return new HashMap<String, String>(attributes);
  }

  private static String httpGet(String path) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + eventPort + path).openConnection();
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

  private static int count(String text, String needle) {
    int occurrences = 0;
    int from = 0;
    while (true) {
      int at = text.indexOf(needle, from);
      if (at < 0) {
        return occurrences;
      }
      occurrences++;
      from = at + needle.length();
    }
  }
}
