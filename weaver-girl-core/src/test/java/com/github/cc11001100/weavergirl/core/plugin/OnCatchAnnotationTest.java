package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.OnCatch;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code @OnCatch} methods are discovered by {@link AnnotationPluginLoader} and
 * registered as catch-block interceptors ({@link InterceptorDefinition.AdviceMode#CATCH}) that
 * {@link com.github.cc11001100.weavergirl.core.CatchAdvice} can dispatch to.
 */
class OnCatchAnnotationTest {

  @WeaveClass(target = "com.example.RiskyService")
  public static class CatchHandlers {
    static final List<String> invocations = Collections.synchronizedList(new ArrayList<>());

    @OnCatch("java.io.IOException")
    public void onIoError(CatchInvocation invocation) {
      invocations.add("io:" + invocation.getCaughtException().getMessage());
    }

    @OnCatch("java.lang.ArithmeticException")
    public void onArithmeticError(CatchInvocation invocation) {
      invocations.add("math:" + invocation.getCaughtException().getMessage());
    }
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new DefaultInterceptorRegistry();
    CatchHandlers.invocations.clear();
  }

  private InterceptorDefinition definitionFor(String methodName) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-onCatch-" + methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + methodName));
  }

  @Test
  void onCatchMethods_registerAsSeparateCatchModeDefinitions() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(CatchHandlers.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(2, defs.size(), "each @OnCatch method should be its own definition");
    for (InterceptorDefinition def : defs) {
      assertEquals(InterceptorDefinition.AdviceMode.CATCH, def.getAdviceMode());
      assertTrue(def.getInterceptor() instanceof CatchInterceptor);
    }
  }

  @Test
  void onCatch_invokesAnnotatedMethodWithCatchInvocation() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(CatchHandlers.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    CatchInterceptor ioInterceptor = (CatchInterceptor) definitionFor("onIoError").getInterceptor();
    CatchInvocation invocation =
        new CatchInvocation(
            CatchHandlers.class, "process", new FileNotFoundException("missing.txt"));
    ioInterceptor.onCatch(invocation);

    assertEquals(List.of("io:missing.txt"), CatchHandlers.invocations);
  }

  @Test
  void catchPointcuts_matchDeclaredExceptionTypeAndSubtypes() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(CatchHandlers.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    CatchInterceptor ioInterceptor = (CatchInterceptor) definitionFor("onIoError").getInterceptor();
    CatchPointcut[] pointcuts = ioInterceptor.catchPointcuts();
    assertEquals(1, pointcuts.length);

    // FileNotFoundException is a subtype of IOException -- must match per @OnCatch's documented
    // "subtypes are also matched" contract.
    assertTrue(
        pointcuts[0].matches(
            "com.example.RiskyService", "process", FileNotFoundException.class));
    assertTrue(pointcuts[0].matches("com.example.RiskyService", "process", IOException.class));
    assertFalse(
        pointcuts[0].matches(
            "com.example.RiskyService", "process", ArithmeticException.class));
  }

  @Test
  void onCatch_doesNotDoubleInvoke_whenMultipleOnCatchMethodsExistInSameClass() {
    // Regression test: previously a single batched CatchInterceptor re-matched every
    // @OnCatch method against the caught exception inside onCatch(), so if CatchAdvice
    // called onCatch() once per matching CatchPointcut, the handler could fire more than
    // once for one exception. Each @OnCatch method must now be its own definition/interceptor.
    Set<Class<?>> classes = new HashSet<>();
    classes.add(CatchHandlers.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    CatchInterceptor mathInterceptor =
        (CatchInterceptor) definitionFor("onArithmeticError").getInterceptor();
    mathInterceptor.onCatch(
        new CatchInvocation(CatchHandlers.class, "divide", new ArithmeticException("/ by zero")));

    assertEquals(List.of("math:/ by zero"), CatchHandlers.invocations);
  }
}
