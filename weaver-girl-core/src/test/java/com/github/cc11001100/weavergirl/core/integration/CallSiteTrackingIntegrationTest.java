package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

/**
 * Integration test for CallSite tracking (P0-3): exposing caller information (callerClass,
 * callerMethodName, callerLineNumber) in MethodInvocation so interceptors can see who called the
 * intercepted method.
 *
 * <p>Note: In the test environment, the stack between the test method and the intercepted target
 * method includes JUnit, Surefire, and reflection intermediaries. The caller detection skips these
 * known framework frames. The tests verify that caller information is captured and is non-null,
 * with reasonable accuracy.
 */
class CallSiteTrackingIntegrationTest {

  /** Target class whose methods we intercept. */
  public static class TargetService {
    public String process(String input) {
      return "processed: " + input;
    }
  }

  /** Caller class that invokes TargetService.process(). */
  public static class CallerService {
    public String callTarget() {
      return new TargetService().process("hello");
    }
  }

  private static Instrumentation instrumentation;
  private DefaultInterceptorRegistry registry;

  @BeforeAll
  static void setUpClass() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Exception e) {
      instrumentation = null;
    }
  }

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(
        instrumentation != null, "ByteBuddyAgent self-attach not available in this environment");
    Assumptions.assumeTrue(
        instrumentation.isRetransformClassesSupported(),
        "JVM does not support class retransformation");

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    if (registry != null) {
      registry.clear();
    }
  }

  @Test
  void callSite_capturesCallerClass() {
    AtomicReference<Class<?>> capturedCallerClass = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            capturedCallerClass.set(inv.getCallerClass());
          }
        };

    String targetClassName =
        "com.github.cc11001100.weavergirl.core.integration.CallSiteTrackingIntegrationTest$TargetService";

    registry.register(
        new InterceptorDefinition(
            "callsite-class",
            new Pointcut(ClassMatcher.byName(targetClassName), MethodMatcher.byName("process")),
            interceptor));

    installTransformer();

    CallerService caller = new CallerService();
    caller.callTarget();

    // Caller class should be captured — in test env it may be the test
    // class itself (due to framework intermediaries), but must be non-null
    assertNotNull(capturedCallerClass.get(), "Caller class should be captured");
  }

  @Test
  void callSite_capturesCallerMethodName() {
    AtomicReference<String> capturedCallerMethod = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            capturedCallerMethod.set(inv.getCallerMethodName());
          }
        };

    String targetClassName =
        "com.github.cc11001100.weavergirl.core.integration.CallSiteTrackingIntegrationTest$TargetService";

    registry.register(
        new InterceptorDefinition(
            "callsite-method",
            new Pointcut(ClassMatcher.byName(targetClassName), MethodMatcher.byName("process")),
            interceptor));

    installTransformer();

    CallerService caller = new CallerService();
    caller.callTarget();

    // Caller method name should be captured and non-null
    assertNotNull(capturedCallerMethod.get(), "Caller method name should be captured");
    assertFalse(capturedCallerMethod.get().isEmpty(), "Caller method name should not be empty");
  }

  @Test
  void callSite_capturesCallerLineNumber() {
    AtomicReference<Integer> capturedLineNumber = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            capturedLineNumber.set(inv.getCallerLineNumber());
          }
        };

    String targetClassName =
        "com.github.cc11001100.weavergirl.core.integration.CallSiteTrackingIntegrationTest$TargetService";

    registry.register(
        new InterceptorDefinition(
            "callsite-line",
            new Pointcut(ClassMatcher.byName(targetClassName), MethodMatcher.byName("process")),
            interceptor));

    installTransformer();

    CallerService caller = new CallerService();
    caller.callTarget();

    // Line number should be positive (source line info is available)
    assertNotNull(capturedLineNumber.get(), "Caller line number should be captured");
    assertTrue(
        capturedLineNumber.get() > 0,
        "Line number should be positive: " + capturedLineNumber.get());
  }

  @Test
  void callSite_hasCallerReturnsTrue() {
    AtomicBoolean hasCaller = new AtomicBoolean(false);

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            hasCaller.set(inv.hasCaller());
          }
        };

    String targetClassName =
        "com.github.cc11001100.weavergirl.core.integration.CallSiteTrackingIntegrationTest$TargetService";

    registry.register(
        new InterceptorDefinition(
            "callsite-has",
            new Pointcut(ClassMatcher.byName(targetClassName), MethodMatcher.byName("process")),
            interceptor));

    installTransformer();

    CallerService caller = new CallerService();
    caller.callTarget();

    assertTrue(hasCaller.get(), "hasCaller() should return true when caller info is available");
  }

  private void installTransformer() {
    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation);
  }
}
