package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for P0-12: {@code ARGUMENT_REWRITE} advice writes back <em>any</em> argument
 * index, not just index 0.
 *
 * <p>Installs a real ByteBuddy transformer (via self-attach) on app-classloader targets and
 * verifies that an interceptor's {@code setArgument(i, ...)} reaches the method body for i = 0, 1
 * and 2 through the full advice pipeline ({@code @Advice.Argument(i, readOnly=false,
 * optional=true)} slots).
 *
 * <p>Each test uses its own dedicated inner class so transformer registrations don't interfere
 * across tests.
 */
class ArgumentRewriteIntegrationTest {

  /** Two-arg target for rewriting the second argument. */
  public static class TwoArgService {
    public String combine(String first, String second) {
      return first + "+" + second;
    }
  }

  /** Two-arg target for rewriting the first argument (old behavior). */
  public static class FirstArgService {
    public String combine(String first, String second) {
      return first + "+" + second;
    }
  }

  /** Three-arg target for rewriting the last argument. */
  public static class ThreeArgService {
    public String join(String a, String b, String c) {
      return a + "+" + b + "+" + c;
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
    assumeTrue(
        instrumentation != null, "ByteBuddyAgent self-attach not available in this environment");
    assumeTrue(
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
  void rewriteSecondArgument_reachesMethodBody() {
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            inv.setArgument(1, "REWRITTEN");
          }
        };

    registerRewrite(
        "rewrite-second",
        TwoArgService.class.getName(),
        "combine",
        "java.lang.String,java.lang.String",
        interceptor);
    installTransformer();

    // Inner class is loaded lazily here — after install — so it gets transformed.
    TwoArgService service = new TwoArgService();
    assertEquals(
        "orig+REWRITTEN",
        service.combine("orig", "second"),
        "setArgument(1) must reach the method body through the woven advice");
  }

  @Test
  void rewriteFirstArgument_stillWorks() {
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            inv.setArgument(0, "REWRITTEN");
          }
        };

    registerRewrite(
        "rewrite-first",
        FirstArgService.class.getName(),
        "combine",
        "java.lang.String,java.lang.String",
        interceptor);
    installTransformer();

    FirstArgService service = new FirstArgService();
    assertEquals(
        "REWRITTEN+second",
        service.combine("first", "second"),
        "setArgument(0) must keep working after the multi-slot change");
  }

  @Test
  void rewriteThirdArgument_reachesMethodBody() {
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            inv.setArgument(2, "REWRITTEN");
          }
        };

    registerRewrite(
        "rewrite-third",
        ThreeArgService.class.getName(),
        "join",
        "java.lang.String,java.lang.String,java.lang.String",
        interceptor);
    installTransformer();

    ThreeArgService service = new ThreeArgService();
    assertEquals(
        "a+b+REWRITTEN",
        service.join("a", "b", "c"),
        "setArgument(2) must reach the method body through the woven advice");
  }

  private void registerRewrite(
      String name,
      String className,
      String methodName,
      String parameterTypes,
      Interceptor interceptor) {
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(
                ClassMatcher.byName(className),
                MethodMatcher.bySignature(methodName, parameterTypes)),
            interceptor,
            0,
            InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE));
  }

  private void installTransformer() {
    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation);
  }
}
