package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.OnConstructor;
import com.github.cc11001100.weavergirl.annotation.RewriteArg;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that the new annotation wiring actually takes effect on live woven code:
 *
 * <ul>
 *   <li>{@code @RewriteArg} rewrites an argument through the real {@code AsyncArgumentAdvice}
 *       pipeline — {@code setArgument} reaches the method body (the {@code @Advice.AllArguments}
 *       path in {@code InterceptAdvice} could never do this).
 *   <li>A {@code @Before} on the same method still fires exactly once — the {@code
 *       ARGUMENT_REWRITE} isolation in {@code InterceptAdvice} prevents double dispatch.
 *   <li>{@code @OnConstructor(parameterTypes=...)} selects a single constructor overload on live
 *       woven code.
 * </ul>
 *
 * <p>Each test uses its own target class so transformer registrations don't interfere.
 */
class RewriteArgWeavingIntegrationTest {

  /** Target for the rewrite + before-coexistence check. */
  public static class RewriteTarget {
    public String greet(String name) {
      return "hello " + name;
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.RewriteArgWeavingIntegrationTest$RewriteTarget")
  public static class RewriteAdvice {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @RewriteArg("greet")
    public void upper(MethodInvocation inv) {
      calls.add("rewrite:" + inv.getArgument(0));
      inv.setArgument(0, ((String) inv.getArgument(0)).toUpperCase());
    }

    @Before("greet")
    public void observe(MethodInvocation inv) {
      calls.add("before:" + inv.getArgument(0));
    }
  }

  /** Target with two constructor overloads for the overload-selection check. */
  public static class CtorTarget {
    final String value;

    public CtorTarget() {
      this.value = "default";
    }

    public CtorTarget(String value) {
      this.value = value;
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.RewriteArgWeavingIntegrationTest$CtorTarget")
  public static class CtorAdvice {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @OnConstructor(parameterTypes = {"java.lang.String"})
    public void onStringCtor(MethodInvocation inv) {
      calls.add("ctor:" + inv.getArgument(0));
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
    org.junit.jupiter.api.Assumptions.assumeTrue(
        instrumentation != null, "ByteBuddyAgent self-attach not available in this environment");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        instrumentation.isRetransformClassesSupported(),
        "JVM does not support class retransformation");
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    RewriteAdvice.calls.clear();
    CtorAdvice.calls.clear();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    if (registry != null) {
      registry.clear();
    }
  }

  private void loadAndWeave(Class<?> adviceClass) {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(adviceClass);
    new AnnotationPluginLoader().loadAnnotatedInterceptors(classes, registry);
    assertFalse(registry.getAllDefinitions().isEmpty(), "advice should register definitions");
    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    // eagerRetransform=true: targets may already be loaded in this test JVM
    transformer.install(instrumentation, true);
  }

  @Test
  void rewriteArg_setArgumentReachesMethodBody_beforeFiresOnce() {
    loadAndWeave(RewriteAdvice.class);
    String result = new RewriteTarget().greet("world");
    assertEquals("hello WORLD", result, "rewritten argument must reach the method body");
    // @Before runs after the rewrite (STANDARD definitions sort alongside the rewrite
    // definition; the rewrite's before() executes on entry via AsyncArgumentAdvice while
    // @Before executes via InterceptAdvice), so it observes the rewritten value — exactly once.
    assertEquals(
        List.of("rewrite:world", "before:WORLD"),
        RewriteAdvice.calls,
        "@RewriteArg and @Before on the same method must each fire exactly once");
  }

  @Test
  void onConstructor_parameterTypes_selectsSingleOverload() {
    loadAndWeave(CtorAdvice.class);
    CtorTarget noArg = new CtorTarget();
    assertEquals("default", noArg.value);
    assertTrue(
        CtorAdvice.calls.isEmpty(), "no-arg constructor must not match the String overload");

    CtorTarget withArg = new CtorTarget("custom");
    assertEquals("custom", withArg.value);
    assertEquals(
        List.of("ctor:custom"), CtorAdvice.calls, "String overload must fire exactly once");
  }
}
