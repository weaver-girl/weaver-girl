package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.AfterFinally;
import com.github.cc11001100.weavergirl.annotation.AfterThrowing;
import com.github.cc11001100.weavergirl.annotation.Before;
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
 * End-to-end proof that annotations actually take effect: {@code @WeaveClass}-annotated advice
 * classes are loaded via {@link AnnotationPluginLoader}, woven into a real target class by {@link
 * WeaverTransformer}, and their callbacks fire on live method invocations — covering the normal,
 * exception, and finally paths.
 */
class AnnotationWeavingIntegrationTest {

  /** Each test gets its own target class so transformer registrations don't interfere. */
  public static class WeaveTarget1 {
    public String greet(String name) {
      return "hello " + name;
    }
  }

  public static class WeaveTarget2 {
    public String fail(String name) {
      throw new IllegalStateException("fail " + name);
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.AnnotationWeavingIntegrationTest$WeaveTarget1")
  public static class GreetAdvice {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @Before("greet")
    public void beforeGreet(MethodInvocation inv) {
      calls.add("before:" + inv.getArgument(0));
    }

    @After("greet")
    public void afterGreet(MethodInvocation inv) {
      calls.add("after:" + inv.getReturnValue());
    }

    @AfterFinally("greet")
    public void finallyGreet(MethodInvocation inv) {
      calls.add("finally");
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.AnnotationWeavingIntegrationTest$WeaveTarget2")
  public static class FailAdvice {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @AfterThrowing("fail")
    public void onFail(MethodInvocation inv) {
      calls.add("afterThrowing:" + inv.getThrowable().getMessage());
    }

    @AfterFinally("fail")
    public void finallyFail(MethodInvocation inv) {
      calls.add("finally");
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
    GreetAdvice.calls.clear();
    FailAdvice.calls.clear();
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
  void annotationAdvice_wovenIntoRealMethod_beforeAfterFinallyFire() {
    loadAndWeave(GreetAdvice.class);
    String result = new WeaveTarget1().greet("world");
    assertEquals("hello world", result);
    assertEquals(
        List.of("before:world", "after:hello world", "finally"), GreetAdvice.calls,
        "before/after/afterFinally should all fire on a real woven invocation");
  }

  @Test
  void annotationAdvice_wovenIntoThrowingMethod_afterThrowingAndFinallyFire() {
    loadAndWeave(FailAdvice.class);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> new WeaveTarget2().fail("x"));
    assertEquals("fail x", thrown.getMessage(), "original exception must propagate");
    assertEquals(
        List.of("afterThrowing:fail x", "finally"), FailAdvice.calls,
        "afterThrowing + afterFinally should fire on a real throwing invocation");
  }
}
