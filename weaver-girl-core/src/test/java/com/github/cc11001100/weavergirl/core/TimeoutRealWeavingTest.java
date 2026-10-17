package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.annotation.Timeout;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.MethodDelegation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that {@code @Timeout} really replaces an overdue return with a {@link
 * TimeoutException} at the caller.
 *
 * <p>The old implementation only attached {@code timeout.exceeded} after the body completed, so a
 * method that slept past its budget returned its stale value to the caller untouched. This test
 * weaves a generated class whose {@code compute()} sleeps 300 ms, guards it with {@code
 * @Timeout(durationMs = 30)}, and asserts the caller observes the timeout exception.
 */
class TimeoutRealWeavingTest {

  @WeaveClass(target = "com.example.timeout.SlowService")
  public static class TimeoutAspect {
    @Timeout(value = "compute", durationMs = 30)
    public void enforceTimeout(MethodInvocation inv) {
      timeoutHookCalls.incrementAndGet();
    }
  }

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("TIMEOUT-WEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  /** Standalone delegate so {@code MethodDelegation} has exactly one source method to consider. */
  public static class SlowTarget {
    static volatile boolean sleep;

    public static int compute() {
      if (sleep) {
        try {
          Thread.sleep(300);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return 42;
    }
  }

  static final AtomicInteger timeoutHookCalls = new AtomicInteger(0);

  private static DefaultInterceptorRegistry registry;
  private static Object instance;
  private static Method computeMethod;

  @BeforeAll
  static void weaveTarget() {
    if (instrumentation == null) {
      return;
    }

    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name("com.example.timeout.SlowService")
            .defineMethod("compute", int.class, Visibility.PUBLIC)
            .intercept(MethodDelegation.to(SlowTarget.class))
            .make()
            .load(TimeoutRealWeavingTest.class.getClassLoader())
            .getLoaded();

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);

    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> aspects = new HashSet<>();
    aspects.add(TimeoutAspect.class);
    loader.loadAnnotatedInterceptors(aspects, registry);

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.install(instrumentation, true);

    try {
      instance = target.getDeclaredConstructor().newInstance();
      computeMethod = target.getMethod("compute");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void setUp() {
    InterceptorHolder.setRegistry(registry);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
    SlowTarget.sleep = false;
    timeoutHookCalls.set(0);
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  private static void resetGlobalInterceptionSwitch() {
    try {
      Field enabledField = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      enabledField.setAccessible(true);
      enabledField.setBoolean(null, true);
      Field toggleCountField = GlobalInterceptionSwitch.class.getDeclaredField("toggleCount");
      toggleCountField.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicLong) toggleCountField.get(null)).set(0);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void timeout_exceeded_callersObserveTimeoutException_notTheStaleReturn() throws Exception {
    SlowTarget.sleep = true; // body sleeps 300ms, way past the 30ms budget

    Throwable thrown =
        assertThrows(InvocationTargetException.class, () -> computeMethod.invoke(instance));
    Throwable cause = thrown.getCause();
    assertInstanceOf(TimeoutException.class, cause, "caller must receive the timeout, not 42");
    assertTrue(cause.getMessage().contains("exceeded 30ms"));
    assertTrue(timeoutHookCalls.get() >= 1, "guard hook must fire on timeout");
  }

  @Test
  void timeout_withinBudget_passesTheReturnThrough() throws Exception {
    SlowTarget.sleep = false; // instant return, well inside the 30ms budget

    Object result = computeMethod.invoke(instance);

    assertEquals(42, result);
    assertEquals(0, timeoutHookCalls.get(), "no timeout -> guard hook must not fire");
  }
}
