package com.github.cc11001100.weavergirl.core.transformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.management.AgentApiServer;
import com.github.cc11001100.weavergirl.core.management.AgentDiagnostics;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import transformsafety.FailLoaded;
import transformsafety.FailTarget;
import transformsafety.OkLoaded;
import transformsafety.OkTarget;

/** One class's transform failure must not sink the JVM, its sibling, or the fault report. */
class TransformIsolationTest {

  private static Instrumentation instrumentation;
  private static AgentApiServer server;
  private static int port;

  @BeforeAll
  static void attach() throws Exception {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("TRANSFORM-ATTACH-FAILED " + failure);
      failure.printStackTrace(System.out);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    server = new AgentApiServer(port);
    server.start();
  }

  @AfterAll
  static void stop() {
    WeaverTransformer.clearForcedFailures();
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void installLeavesFailedClassAndEnhancesSibling() throws Exception {
    assertTrue(WeaverTransformer.isNeverRewritten("net.bytebuddy.agent.ByteBuddyAgent"));
    assertTrue(WeaverTransformer.isNeverRewritten("org.objectweb.asm.ClassWriter"));
    assertTrue(
        WeaverTransformer.isNeverRewritten(
            "com.github.cc11001100.weavergirl.shade.org.slf4j.Logger"));
    assertTrue(
        WeaverTransformer.isNeverRewritten(
            "com.github.cc11001100.weavergirl.agent.WeaverGirlAgent"));
    assertTrue(WeaverTransformer.isNeverRewritten("com.github.cc11001100.weavergirl.core.WeaverGirl"));
    assertFalse(WeaverTransformer.isNeverRewritten(FailTarget.class.getName()));

    assertEquals("fail", FailTarget.ping());
    assertEquals("ok", OkTarget.ping());
    OkTarget.seen = false;

    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    register(registry, FailTarget.class.getName(), "fail-hook", null);
    register(
        registry,
        OkTarget.class.getName(),
        "ok-hook",
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            OkTarget.seen = true;
          }
        });
    WeaverTransformer.failTransform(
        FailTarget.class.getName(), new ClassFormatError("bad bytecode for FailTarget"));

    AgentDiagnostics.getInstance().clearFaults();
    long before = AgentStatus.getInstance().getTransformationErrorCount();
    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(true);
    transformer.install(instrumentation, true);

    assertEquals("fail", FailTarget.ping());
    assertEquals("ok", OkTarget.ping());
    assertTrue(OkTarget.seen, "healthy class must still be enhanced");
    assertTrue(AgentStatus.getInstance().getTransformationErrorCount() > before);

    String faults = get("/diagnostics/faults");
    String again = get("/diagnostics/faults");
    System.out.println("TRANSFORM-FAULTS " + faults);
    System.out.println("TRANSFORM-FAULTS-AGAIN " + again);
    assertEquals(faults, again);
    assertTrue(faults.contains(FailTarget.class.getName()));
    assertTrue(faults.contains("ClassFormatError"));
    assertFalse(faults.contains(",}"));
    assertFalse(faults.contains(",]"));
    int records = count(faults, "\"type\":");
    assertTrue(faults.contains("\"count\":" + records));
    assertTrue(records >= 1);

    String jvm = get("/jvm");
    System.out.println("TRANSFORM-JVM " + jvm);
    assertTrue(jvm.contains("\"heapUsed\":"));
    assertTrue(jvm.contains("\"nonHeapUsed\":"));
    assertTrue(jvm.contains("\"threadCount\":"));
    assertTrue(jvm.contains("\"garbageCollectors\":["));
  }

  @Test
  void retransformContinuesAfterOneClassFails() throws Exception {
    assertEquals("fail-loaded", FailLoaded.ping());
    assertEquals("ok-loaded", OkLoaded.ping());
    OkLoaded.seen = false;

    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    register(registry, FailLoaded.class.getName(), "fail-loaded-hook", null);
    register(
        registry,
        OkLoaded.class.getName(),
        "ok-loaded-hook",
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            OkLoaded.seen = true;
          }
        });
    WeaverTransformer.failTransform(
        FailLoaded.class.getName(), new ClassFormatError("bad bytecode for FailLoaded"));

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(true);
    transformer.install(instrumentation, false);

    AgentDiagnostics.getInstance().clearFaults();
    long before = AgentStatus.getInstance().getTransformationErrorCount();
    int changed = transformer.retransformLoadedClasses();
    assertTrue(changed >= 1, "sibling class must be retransformed");

    assertEquals("fail-loaded", FailLoaded.ping());
    assertEquals("ok-loaded", OkLoaded.ping());
    assertTrue(OkLoaded.seen, "sibling in the same batch must be retransformed");
    assertTrue(AgentStatus.getInstance().getTransformationErrorCount() > before);

    String faults = get("/diagnostics/faults");
    System.out.println("TRANSFORM-RETRANSFORM-FAULTS " + faults);
    assertTrue(faults.contains(FailLoaded.class.getName()));
    assertTrue(faults.contains("ClassFormatError"));
    assertFalse(faults.contains(",}"));
    assertFalse(faults.contains(",]"));
  }

  private static void register(
      DefaultInterceptorRegistry registry, String className, String name, Interceptor interceptor) {
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName("ping")),
            interceptor == null ? new Interceptor() {} : interceptor,
            10));
  }

  private static String get(String path) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    connection.setRequestMethod("GET");
    assertEquals(200, connection.getResponseCode());
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
    int found = 0;
    int from = 0;
    while (true) {
      int at = text.indexOf(needle, from);
      if (at < 0) {
        return found;
      }
      found++;
      from = at + needle.length();
    }
  }
}
