package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two interceptor definitions on one method must not make the method run twice.
 *
 * <p>The transformer used to register one transformation per definition, and each one wove {@code
 * InterceptAdvice} into the method again. Advice dispatches to every matching interceptor itself, so
 * the second copy only repeated the work: the method body ran once per definition and every
 * callback fired that many times. Plugins that each watch the same call — timing plus auditing, for
 * instance — tripped this as soon as a second one was added.
 */
class MultiInterceptorWeavingTest {

  /** Counts its own executions, so a double weave shows up as two increments per call. */
  public static class Counter {
    static final AtomicInteger calls = new AtomicInteger();

    public int hit() {
      return calls.incrementAndGet();
    }

    /** Returns its argument unchanged. A skipping interceptor replaces that. */
    public String echo(String value) {
      calls.incrementAndGet();
      return value;
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
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    Counter.calls.set(0);
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    if (registry != null) {
      registry.clear();
    }
  }

  @Test
  void twoInterceptorsOnOneMethod_runTheBodyOnceAndEachCallbackOnce() {
    String className =
        "com.github.cc11001100.weavergirl.core.integration.MultiInterceptorWeavingTest$Counter";
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();

    registry.register(definition("first", className, first));
    registry.register(definition("second", className, second));

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation);

    int returned = new Counter().hit();

    assertEquals(1, returned, "the method body ran more than once for a single call");
    assertEquals(1, Counter.calls.get(), "the method body ran more than once for a single call");
    assertEquals(1, first.get(), "the first interceptor's callback did not fire exactly once");
    assertEquals(1, second.get(), "the second interceptor's callback did not fire exactly once");
  }

  @Test
  void beforeAdvice_skipMethod_stopsTheBodyAndReturnsTheFallback() {
    String className =
        "com.github.cc11001100.weavergirl.core.integration.MultiInterceptorWeavingTest$Counter";

    Interceptor skipping =
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            invocation.skipMethod();
            invocation.setReturnValue("fallback");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "skipper",
            new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName("echo")),
            skipping));

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation);

    String returned = new Counter().echo("original");

    assertEquals("fallback", returned, "skipMethod() in before advice did not replace the result");
    assertEquals(0, Counter.calls.get(), "skipMethod() in before advice did not stop the body");
  }

  private static InterceptorDefinition definition(
      String name, String className, AtomicInteger seen) {
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            seen.incrementAndGet();
          }
        };
    return new InterceptorDefinition(
        name,
        new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName("hit")),
        interceptor);
  }
}
