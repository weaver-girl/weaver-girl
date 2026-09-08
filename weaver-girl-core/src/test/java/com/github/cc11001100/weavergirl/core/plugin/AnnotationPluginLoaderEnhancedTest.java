package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnnotationPluginLoaderEnhancedTest {

  private AnnotationPluginLoader loader;
  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new DefaultInterceptorRegistry();
  }

  // --- Test interceptor classes using new annotations ---

  @WeaveClass(target = "com.example.OrderedService")
  @Order(42)
  public static class OrderedInterceptor {
    @Before("process")
    public void beforeProcess(MethodInvocation inv) {}
  }

  @WeaveClass(target = "com.example.ErrorProneService")
  public static class OnExceptionInterceptor {
    static boolean exceptionHandlerCalled = false;
    static Throwable capturedException = null;

    @OnException("riskyMethod")
    public void handleError(MethodInvocation inv) {
      exceptionHandlerCalled = true;
      capturedException = inv.getThrowable();
    }
  }

  @WeaveClass(target = "com.example.ResultService")
  public static class AfterReturningInterceptor {
    static boolean afterReturningCalled = false;
    static boolean afterCalled = false;
    static Object capturedReturnValue = null;

    @AfterReturning("compute")
    public void transformResult(MethodInvocation inv) {
      afterReturningCalled = true;
      capturedReturnValue = inv.getReturnValue();
    }

    @After("compute")
    public void afterCompute(MethodInvocation inv) {
      afterCalled = true;
    }
  }

  @WeaveClass(target = "com.example.MixedAdviceService")
  @Order(5)
  public static class MixedAdviceInterceptor {
    static boolean beforeCalled = false;
    static boolean onExceptionCalled = false;
    static boolean afterReturningCalled = false;

    @Before("execute")
    public void beforeExecute(MethodInvocation inv) {
      beforeCalled = true;
    }

    @OnException("execute")
    public void onExecuteError(MethodInvocation inv) {
      onExceptionCalled = true;
    }

    @AfterReturning("execute")
    public void onExecuteSuccess(MethodInvocation inv) {
      afterReturningCalled = true;
    }
  }

  // --- Tests ---

  @Test
  void orderAnnotation_shouldSetPriorityOnDefinition() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(OrderedInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals(42, defs.get(0).getPriority(), "@Order(42) should set priority to 42");
  }

  @Test
  void onException_shouldBeCalledInOnExceptionCallback() {
    OnExceptionInterceptor.exceptionHandlerCalled = false;
    OnExceptionInterceptor.capturedException = null;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(OnExceptionInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();
    MethodInvocation invocation =
        new MethodInvocation(String.class, "riskyMethod", "target", new Object[0]);
    invocation.setThrowable(new RuntimeException("test error"));

    interceptor.onException(invocation);

    assertTrue(
        OnExceptionInterceptor.exceptionHandlerCalled,
        "@OnException method should be called in onException callback");
    assertNotNull(OnExceptionInterceptor.capturedException, "Should capture the thrown exception");
    assertEquals("test error", OnExceptionInterceptor.capturedException.getMessage());
  }

  @Test
  void afterReturning_shouldBeCalledInAfterCallback_onlyOnSuccess() {
    AfterReturningInterceptor.afterReturningCalled = false;
    AfterReturningInterceptor.afterCalled = false;
    AfterReturningInterceptor.capturedReturnValue = null;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(AfterReturningInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();
    MethodInvocation invocation =
        new MethodInvocation(String.class, "compute", "target", new Object[0]);
    invocation.initReturnValue("result-42");

    // Call after (success path) — both @After and @AfterReturning should fire
    interceptor.after(invocation);

    assertTrue(
        AfterReturningInterceptor.afterReturningCalled,
        "@AfterReturning should be called in after() on success");
    assertTrue(AfterReturningInterceptor.afterCalled, "@After should also be called in after()");
    assertEquals(
        "result-42",
        AfterReturningInterceptor.capturedReturnValue,
        "Should capture the return value");
  }

  @Test
  void afterReturning_shouldNotBeCalledInOnException() {
    AfterReturningInterceptor.afterReturningCalled = false;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(AfterReturningInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();
    MethodInvocation invocation =
        new MethodInvocation(String.class, "compute", "target", new Object[0]);
    invocation.setThrowable(new RuntimeException("boom"));

    // Call onException — @AfterReturning should NOT fire
    interceptor.onException(invocation);

    assertFalse(
        AfterReturningInterceptor.afterReturningCalled,
        "@AfterReturning should NOT be called in onException()");
  }

  @Test
  void mixedAdvice_allCallbacksWorkCorrectly() {
    MixedAdviceInterceptor.beforeCalled = false;
    MixedAdviceInterceptor.onExceptionCalled = false;
    MixedAdviceInterceptor.afterReturningCalled = false;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(MixedAdviceInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    assertEquals(1, registry.getAllDefinitions().size());
    assertEquals(
        5, registry.getAllDefinitions().get(0).getPriority(), "@Order(5) should set priority to 5");

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();

    // Test before
    MethodInvocation beforeInv =
        new MethodInvocation(String.class, "execute", "target", new Object[0]);
    interceptor.before(beforeInv);
    assertTrue(MixedAdviceInterceptor.beforeCalled);

    // Test onException
    MethodInvocation exInv = new MethodInvocation(String.class, "execute", "target", new Object[0]);
    exInv.setThrowable(new RuntimeException("fail"));
    interceptor.onException(exInv);
    assertTrue(MixedAdviceInterceptor.onExceptionCalled);

    // Test after (success) — @AfterReturning should fire
    MethodInvocation afterInv =
        new MethodInvocation(String.class, "execute", "target", new Object[0]);
    interceptor.after(afterInv);
    assertTrue(MixedAdviceInterceptor.afterReturningCalled);
  }

  @Test
  void noOrderAnnotation_shouldDefaultToPriorityZero() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(OnExceptionInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    assertEquals(
        0,
        registry.getAllDefinitions().get(0).getPriority(),
        "Without @Order, priority should default to 0");
  }
}
