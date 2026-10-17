package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.annotation.RetryOnException;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * End-to-end proof that {@code @RetryOnException} actually re-invokes the woven target method
 * body instead of only suppressing the failure.
 *
 * <p>The old implementation ran {@code wrapWithRetry.onException}, which recorded the attempt and
 * suppressed the exception but never re-executed the original method. The javadoc promises
 * "re-invoke the original method up to the specified number of retries", so this test weaves a
 * generated class whose {@code ping()} throws for the first {@link #failTimes} calls and then
 * succeeds, and asserts the method body was really executed {@code failTimes + 1} times.
 */
class RetryRealWeavingTest {

  @WeaveClass(target = "com.example.retry.FlakyService")
  public static class RetryAspect {
    @RetryOnException(value = "ping", maxRetries = 2, delayMs = 0)
    public void retryPing(MethodInvocation inv) {
      retryHookCalls.add("retry:" + inv.getMethodName());
    }
  }

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("RETRY-WEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  /**
   * Standalone delegate so {@code MethodDelegation} has exactly one source method to consider.
   */
  public static class FlakyTarget {
    /** Number of consecutive calls to {@code ping()} that fail before it succeeds. */
    static volatile int failTimes = 0;

    public static String flakyPing() {
      int n = pingCalls.incrementAndGet();
      if (n <= failTimes) {
        throw new RuntimeException("boom-" + n);
      }
      return "ok-" + n;
    }
  }

  static final AtomicInteger pingCalls = new AtomicInteger(0);
  static final List<String> retryHookCalls = new ArrayList<>();

  private static DefaultInterceptorRegistry registry;
  private static Object instance;
  private static Method pingMethod;

  @BeforeAll
  static void weaveTarget() {
    if (instrumentation == null) {
      return;
    }

    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name("com.example.retry.FlakyService")
            .defineMethod("ping", String.class, Visibility.PUBLIC)
            .intercept(MethodDelegation.to(FlakyTarget.class))
            .make()
            .load(RetryRealWeavingTest.class.getClassLoader())
            .getLoaded();

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);

    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> aspects = new HashSet<>();
    aspects.add(RetryAspect.class);
    loader.loadAnnotatedInterceptors(aspects, registry);

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.install(instrumentation, true);

    try {
      instance = target.getDeclaredConstructor().newInstance();
      pingMethod = target.getMethod("ping");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void setUp() {
    // Dispatch during the woven call needs the registry that wove the target.
    InterceptorHolder.setRegistry(registry);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
        FlakyTarget.failTimes = 0;
    pingCalls.set(0);
    retryHookCalls.clear();
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
  void retry_reinvokesBody_untilSuccess() throws Exception {
    FlakyTarget.failTimes = 2; // attempts 1 and 2 fail, the retry's third execution succeeds

    Object result = pingMethod.invoke(instance);

    assertEquals("ok-3", result);
    assertEquals(3, pingCalls.get(), "body must actually run 3 times (initial + 2 retries)");
    assertEquals(2, retryHookCalls.size(), "@RetryOnException hook fires on each retry");
    assertTrue(retryHookCalls.stream().allMatch(h -> h.startsWith("retry:ping")));
  }

  @Test
  void retry_exhausted_propagatesExceptionToCaller() throws Exception {
    FlakyTarget.failTimes = Integer.MAX_VALUE; // every execution fails

    Throwable thrown =
        assertThrows(InvocationTargetException.class, () -> pingMethod.invoke(instance));
    Throwable cause = thrown.getCause();
    assertNotNull(cause, "the failing RuntimeException must propagate to the caller");
    assertTrue(cause instanceof RuntimeException);
    assertEquals("boom-3", cause.getMessage());
    // maxRetries=2 -> initial call + 2 retries = 3 body executions, then propagation.
    assertEquals(3, pingCalls.get(), "must stop retrying after maxRetries are exhausted");
  }

  @Test
  void retry_noFailure_runsOnce_noHook() throws Exception {
    Object result = pingMethod.invoke(instance);

    assertEquals("ok-1", result);
    assertEquals(1, pingCalls.get(), "success needs no retry");
    assertTrue(retryHookCalls.isEmpty(), "no @RetryOnException hook when nothing is retried");
  }
}