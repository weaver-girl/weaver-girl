package com.github.cc11001100.weavergirl.plugins.iast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.taint.CommandExecutionSink;
import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintBridge;
import com.github.cc11001100.weavergirl.api.taint.TaintFindings;
import com.github.cc11001100.weavergirl.api.taint.TaintRange;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
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
    assertTrue(
        TaintInstrumentation.isConcatInstrumented(),
        "the invokedynamic concat transformer must be registered with the agent");

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

  /**
   * The bridge runs from the bootstrap loader while {@code Taint} stays with the application. A
   * package-private bridge member made this call throw {@code IllegalAccessError}; the public
   * bridge surface is what keeps a product's own reporting code working in that layout.
   */
  /**
   * String transforms and {@code StringBuilder} mutations run through the real JDK advice: a
   * transform keeps the flow, and a mutation moves the range instead of leaving a stale slice.
   */
  @Test
  void transformAndMutationAdviceKeepTheFlowAndItsOffsets() {
    Taint.openScope();
    try {
      String param = new String("payload");
      Taint.tag(param, Taint.PARAMETER_PREFIX + "x");

      assertTrue(Taint.isTainted(param.trim()), "trim keeps the flow");
      assertTrue(Taint.isTainted(param.toUpperCase()), "upper case keeps the flow");
      assertTrue(Taint.isTainted(param.replace('a', 'b')), "replace(char, char) keeps the flow");
      assertTrue(
          Taint.isTainted(param.replace("pay", "PAY")), "replace keeps the flow");
      assertTrue(Taint.isTainted(param.replaceAll("a", "b")), "replaceAll keeps the flow");
      assertTrue(Taint.isTainted(String.join("-", "a", param)), "join keeps the flow");

      StringBuilder deleted = new StringBuilder("echo:").append(param);
      deleted.delete(0, 1);
      String afterDelete = deleted.toString();
      assertEquals("cho:payload", afterDelete);
      FINDINGS.clear();
      CommandExecutionSink.observe(afterDelete);
      assertEquals(1, FINDINGS.size());
      assertEquals("4", FINDINGS.get(0).getAttributes().get("start"));
      assertEquals("11", FINDINGS.get(0).getAttributes().get("end"));

      StringBuilder inserted = new StringBuilder().append(param);
      inserted.insert(0, "xx");
      assertEquals("xxpayload", inserted.toString());
      List<TaintRange> insertRanges = Taint.ranges(inserted.toString());
      assertEquals(2, insertRanges.get(0).getStart());
      assertEquals(2 + param.length(), insertRanges.get(0).getEnd());

      StringBuilder replaced = new StringBuilder("a").append(param);
      replaced.replace(0, 1, "long");
      assertEquals("longpayload", replaced.toString());
      List<TaintRange> replaceRanges = Taint.ranges(replaced.toString());
      assertEquals("long".length(), replaceRanges.get(0).getStart());
      assertEquals("long".length() + param.length(), replaceRanges.get(0).getEnd());

      StringBuilder clipped = new StringBuilder().append(param).append("tail");
      clipped.setLength(3);
      List<TaintRange> clipRanges = Taint.ranges(clipped.toString());
      assertEquals(1, clipRanges.size());
      assertEquals(0, clipRanges.get(0).getStart());
      assertEquals(3, clipRanges.get(0).getEnd());

      StringBuilder reversed = new StringBuilder("ab").append(param);
      reversed.reverse();
      List<TaintRange> reverseRanges = Taint.ranges(reversed.toString());
      assertEquals(0, reverseRanges.get(0).getStart());
      assertEquals(param.length(), reverseRanges.get(0).getEnd());
      System.out.println(
          "IAST mutation delete-start="
              + FINDINGS.get(0).getAttributes().get("start")
              + " delete-end="
              + FINDINGS.get(0).getAttributes().get("end"));
    } finally {
      Taint.closeScope();
    }
  }

  /**
   * Java 9+ lowers {@code +} to {@code invokedynamic}, which no {@code StringBuilder} advice can
   * see. The class is compiled at runtime (the test sources target Java 8) and defined after the
   * agent is installed, so this exercises the registered transformer, not just its unit.
   */
  @Test
  void javaNineConcatenationKeepsTheFlowThroughTheRegisteredTransformer() throws Exception {
    Path dir = Files.createTempDirectory("iast-indy-probe");
    try {
      Path source = dir.resolve("IndyConcatProbe.java");
      Files.write(
          source,
          Arrays.asList(
              "public final class IndyConcatProbe {",
              "  public static String run(String s) { return \"cmd:\" + s; }",
              "}"));
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      assertTrue(compiler != null, "tests run on a JDK");
      assertEquals(
          0,
          compiler.run(null, null, null, "-d", dir.toString(), source.toString()),
          "probe compilation failed");
      byte[] bytes = Files.readAllBytes(dir.resolve("IndyConcatProbe.class"));

      Class<?> probe = new ProbeLoader().define("IndyConcatProbe", bytes);
      Taint.openScope();
      try {
        String param = new String("payload");
        Taint.tag(param, Taint.PARAMETER_PREFIX + "q");
        String result = (String) probe.getMethod("run", String.class).invoke(null, param);
        assertEquals("cmd:payload", result);
        assertTrue(Taint.isTainted(result), "the indy concat result must keep the flow");
        List<TaintRange> ranges = Taint.ranges(result);
        assertEquals(1, ranges.size());
        assertEquals("cmd:".length(), ranges.get(0).getStart());
        assertEquals("cmd:".length() + param.length(), ranges.get(0).getEnd());
        assertEquals(Taint.PARAMETER_PREFIX + "q", ranges.get(0).getSource());
        System.out.println(
            "IAST indy source="
                + ranges.get(0).getSource()
                + " start="
                + ranges.get(0).getStart()
                + " end="
                + ranges.get(0).getEnd());
      } finally {
        Taint.closeScope();
      }
    } finally {
      try {
        Files.walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path -> path.toFile().delete());
      } catch (java.io.IOException ignored) {
        // best-effort cleanup
      }
    }
  }

  /**
   * Constructors and array round trips are part of the modern string toolkit: a builder seeded from
   * a tainted string, a {@code StringBuffer} (not a {@code StringBuilder}), and a
   * {@code char[]}/{@code byte[]} copy all keep the flow.
   */
  @Test
  void builderConstructorsStringBufferAndArrayRoundTripsKeepTheFlow() {
    Taint.openScope();
    try {
      String param = new String("payload");
      Taint.tag(param, Taint.PARAMETER_PREFIX + "b");

      StringBuilder seeded = new StringBuilder(param);
      List<TaintRange> ranges = Taint.ranges(seeded.toString());
      assertEquals(1, ranges.size(), "a constructor seeds the builder with the slice");
      assertEquals(0, ranges.get(0).getStart());
      assertEquals(param.length(), ranges.get(0).getEnd());
      assertEquals(Taint.PARAMETER_PREFIX + "b", ranges.get(0).getSource());

      StringBuffer buffer = new StringBuffer(param);
      buffer.append("-x");
      assertEquals("payload-x", buffer.toString());
      ranges = Taint.ranges(buffer.toString());
      assertEquals(1, ranges.size());
      assertEquals(0, ranges.get(0).getStart());
      assertEquals(param.length(), ranges.get(0).getEnd());

      byte[] bytes = param.getBytes();
      assertTrue(Taint.isTainted(bytes), "getBytes keeps an object-level mark");
      String decoded = new String(bytes);
      assertTrue(Taint.isTainted(decoded), "a String built from the bytes inherits the mark");
      assertEquals(param, decoded);

      char[] chars = param.toCharArray();
      assertTrue(Taint.isTainted(chars), "toCharArray mirrors the slices");
      String copy = new String(chars);
      ranges = Taint.ranges(copy);
      assertEquals(1, ranges.size());
      assertEquals(0, ranges.get(0).getStart());
      assertEquals(param.length(), ranges.get(0).getEnd());

      String middle = new String(chars, 2, 3);
      ranges = Taint.ranges(middle);
      assertEquals(1, ranges.size());
      assertEquals(0, ranges.get(0).getStart());
      assertEquals(3, ranges.get(0).getEnd());

      StringBuilder appended = new StringBuilder("x:");
      appended.append(chars);
      ranges = Taint.ranges(appended.toString());
      assertEquals(1, ranges.size());
      assertEquals(2, ranges.get(0).getStart());
      assertEquals(2 + param.length(), ranges.get(0).getEnd());

      StringBuilder fromBuffer = new StringBuilder("y:");
      fromBuffer.append(new StringBuffer(param));
      ranges = Taint.ranges(fromBuffer.toString());
      assertEquals(1, ranges.size(), "append(StringBuffer) is a separate overload");
      assertEquals(2, ranges.get(0).getStart());
      assertEquals(2 + param.length(), ranges.get(0).getEnd());

      StringBuilder replaced = new StringBuilder(param);
      replaced.setCharAt(3, 'X');
      ranges = Taint.ranges(replaced.toString());
      assertEquals(2, ranges.size(), "the replaced character drops out of the range");
      assertEquals(3, ranges.get(0).getEnd());
      assertEquals(4, ranges.get(1).getStart());

      String fromChars = String.valueOf(chars);
      ranges = Taint.ranges(fromChars);
      assertEquals(1, ranges.size(), "valueOf(char[]) keeps the slices of the array");
      assertEquals(0, ranges.get(0).getStart());
      assertEquals(param.length(), ranges.get(0).getEnd());

      Integer tagged = Integer.valueOf(42);
      Taint.tag(tagged, Taint.PARAMETER_PREFIX + "boxed");
      String fromObject = String.valueOf((Object) tagged);
      ranges = Taint.ranges(fromObject);
      assertEquals(1, ranges.size(), "valueOf(Object) covers the rendered text");
      assertEquals(0, ranges.get(0).getStart());
      assertEquals(fromObject.length(), ranges.get(0).getEnd());

      StringBuilder inserted = new StringBuilder("ab");
      inserted.insert(1, param);
      ranges = Taint.ranges(inserted.toString());
      assertEquals(1, ranges.size(), "insert(int,CharSequence) copies the inserted slice");
      assertEquals(1, ranges.get(0).getStart());
      assertEquals(1 + param.length(), ranges.get(0).getEnd());

      StringBuilder insertedChars = new StringBuilder("ab");
      insertedChars.insert(1, chars);
      ranges = Taint.ranges(insertedChars.toString());
      assertEquals(1, ranges.size(), "insert(int,char[]) copies the inserted slice");
      assertEquals(1, ranges.get(0).getStart());
      assertEquals(1 + param.length(), ranges.get(0).getEnd());

      System.out.println(
          "IAST builder/array source="
              + ranges.get(0).getSource()
              + " insert-start="
              + ranges.get(0).getStart()
              + " insert-end="
              + ranges.get(0).getEnd());
    } finally {
      Taint.closeScope();
    }
  }

  /**
   * {@code String.getChars} writes into a caller-owned array, and {@code insert(int,char[],int,int)}
   * copies only a window of the array. Both must map marks onto the written window.
   */
  @Test
  void getCharsAndInsertRangeMapTheirWindows() {
    Taint.openScope();
    try {
      String param = new String("abcdef");
      Taint.tag(param, Taint.PARAMETER_PREFIX + "w");

      char[] dest = new char[8];
      param.getChars(1, 4, dest, 2);
      List<TaintRange> ranges = Taint.ranges(dest);
      assertEquals(1, ranges.size(), "getChars writes its window into the array");
      assertEquals(2, ranges.get(0).getStart());
      assertEquals(5, ranges.get(0).getEnd());

      char[] chars = param.toCharArray();
      StringBuilder builder = new StringBuilder("ab");
      builder.insert(1, chars, 2, 3);
      ranges = Taint.ranges(builder);
      assertEquals(1, ranges.size(), "insert(int,char[],int,int) copies just its window");
      assertEquals(1, ranges.get(0).getStart());
      assertEquals(4, ranges.get(0).getEnd());
      assertEquals("acdeb", builder.toString());
      System.out.println(
          "IAST getChars/insert-range start="
              + ranges.get(0).getStart()
              + " end="
              + ranges.get(0).getEnd());
    } finally {
      Taint.closeScope();
    }
  }

  /**
   * An exception thrown inside an instrumented JDK call must not strand the re-entrancy guard:
   * before the fix, every later propagation on that thread was skipped for the rest of the request.
   */
  @Test
  void exceptionInsideAnInstrumentedCallReleasesTheGuard() {
    Taint.openScope();
    try {
      String param = new String("abcdef");
      Taint.tag(param, Taint.PARAMETER_PREFIX + "e");
      boolean threw = false;
      try {
        param.substring(4, 2);
      } catch (IndexOutOfBoundsException expected) {
        threw = true;
      }
      assertTrue(threw, "the probe must actually throw");

      char[] chars = param.toCharArray();
      boolean constructorThrew = false;
      try {
        new String(chars, 4, 10);
      } catch (IndexOutOfBoundsException expected) {
        constructorThrew = true;
      }
      assertTrue(constructorThrew, "the constructor probe must actually throw");

      String derived = "pre:" + param;
      assertTrue(
          Taint.isTainted(derived),
          "a later concatenation still propagates after the guarded call threw");
      List<TaintRange> ranges = Taint.ranges(derived);
      assertEquals("pre:".length(), ranges.get(0).getStart());
      assertEquals("pre:".length() + param.length(), ranges.get(0).getEnd());
      System.out.println("IAST guard released after throw, ranges=" + ranges.size());
    } finally {
      Taint.closeScope();
    }
  }

  /** One-shot scheduled tasks carry the submitting scope, like any other executor submission. */
  @Test
  void scheduledTasksCarryTheSubmittingScope() throws Exception {
    final String param = new String("payload");
    final String label = Taint.PARAMETER_PREFIX + "s";
    ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
    try {
      Taint.openScope();
      Taint.tag(param, label);
      Future<Boolean> seen =
          pool.schedule(
              new Callable<Boolean>() {
                @Override
                public Boolean call() {
                  return Boolean.valueOf(Taint.hasSource(param, label));
                }
              },
              10,
              TimeUnit.MILLISECONDS);
      assertTrue(
          seen.get(5, TimeUnit.SECONDS).booleanValue(),
          "a scheduled worker sees the submitting scope");
      System.out.println("IAST scheduled task carried source=" + label);
    } finally {
      Taint.closeScope();
      pool.shutdownNow();
    }
  }

  /**
   * The agent wraps executor submissions so a request-scoped tag survives the hand-off to a pool
   * thread, and a later clean submission does not inherit the previous task's scope.
   */
  @Test
  void executorSubmissionsCarryTheSubmittingScopeIntoTheWorker() throws Exception {
    assertTrue(
        TaintInstrumentation.isExecutorInstrumented(),
        "executor task wrapping must be installed with the taint instrumentation");
    final String param = new String("payload");
    final String label = Taint.PARAMETER_PREFIX + "t";
    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Taint.openScope();
      Taint.tag(param, label);
      Future<Boolean> seen =
          pool.submit(
              new Callable<Boolean>() {
                @Override
                public Boolean call() {
                  return Boolean.valueOf(Taint.hasSource(param, label));
                }
              });
      assertTrue(seen.get(5, TimeUnit.SECONDS).booleanValue(), "worker sees the submitting scope");
      Taint.closeScope();

      Future<Boolean> clean =
          pool.submit(
              new Callable<Boolean>() {
                @Override
                public Boolean call() {
                  return Boolean.valueOf(Taint.hasScope());
                }
              });
      assertFalse(
          clean.get(5, TimeUnit.SECONDS).booleanValue(),
          "a submission without a scope does not inherit the previous one");
      System.out.println("IAST executor carried source=" + label + " into the worker");
    } finally {
      pool.shutdownNow();
      for (int i = 0; i < 8; i++) {
        Taint.closeScope();
      }
    }
  }

  /** Fork-join tasks and {@code CompletableFuture} stages are submitted through the same hook. */
  @Test
  void commonPoolAndCompletableFutureCarryTheScope() throws Exception {
    final String param = new String("payload");
    final String label = Taint.PARAMETER_PREFIX + "f";
    Taint.openScope();
    try {
      Taint.tag(param, label);
      Future<Boolean> forkJoin =
          ForkJoinPool.commonPool()
              .submit(
                  new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                      return Boolean.valueOf(Taint.hasSource(param, label));
                    }
                  });
      assertTrue(forkJoin.get(5, TimeUnit.SECONDS).booleanValue(), "common pool sees the scope");

      CompletableFuture<Boolean> stage =
          CompletableFuture.supplyAsync(
              new Supplier<Boolean>() {
                @Override
                public Boolean get() {
                  return Boolean.valueOf(Taint.hasSource(param, label));
                }
              },
              ForkJoinPool.commonPool());
      assertTrue(stage.get(5, TimeUnit.SECONDS).booleanValue(), "a future stage sees the scope");
      System.out.println("IAST common-pool and future carried source=" + label);
    } finally {
      Taint.closeScope();
    }
  }

  @Test
  void rangesStayReadableAfterTheBridgeMovesToTheBootstrapLoader() {
    assertTrue(TaintInstrumentation.isJdkInstrumented(), "the bootstrap bridge must be installed");
    Taint.openScope();
    try {
      String value = new String("ranges");
      Taint.tag(value, Taint.PARAMETER_PREFIX + "q");
      assertEquals(1, Taint.ranges(value).size());
      assertEquals(Taint.PARAMETER_PREFIX + "q", Taint.ranges(value).get(0).getSource());
      assertEquals(0, Taint.ranges(value).get(0).getStart());
      assertEquals(value.length(), Taint.ranges(value).get(0).getEnd());
      assertTrue(Taint.untaint(value));
      assertFalse(Taint.isTainted(value));
    } finally {
      Taint.closeScope();
    }
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
    // Servlet starts a child of the caller's span. The finding is stamped with that child.
    String stampedSpanId = attributes.get("spanId");
    assertTrue(stampedSpanId != null && !stampedSpanId.isEmpty(), "finding must carry a span id");
    assertTrue(listing.contains("\"spanId\":\"" + stampedSpanId + "\""));
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

  /** Defines runtime-compiled bytes so the agent's transformers see a real class load. */
  private static final class ProbeLoader extends ClassLoader {
    ProbeLoader() {
      super(IastHookLaunchTest.class.getClassLoader());
    }

    Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
