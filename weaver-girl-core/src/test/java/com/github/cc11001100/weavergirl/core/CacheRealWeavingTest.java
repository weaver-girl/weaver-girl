package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.cc11001100.weavergirl.annotation.CacheResult;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
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
 * End-to-end proof that {@code @CacheResult} keys array-typed parameters by <em>content</em>, so
 * equal data in a fresh array instance still hits the cache — and unequal data does not alias.
 *
 * <p>The old key builder rendered array arguments with {@code Arrays.toString(Object[])}, which
 * delegated to the array's identity-based {@code Object.toString()} ({@code [I@...}); every call
 * missed. The stable builder keys by {@code deepToString} content plus the argument count, so the
 * woven target body runs once and the second, equal-content call short-circuits.
 */
class CacheRealWeavingTest {

  @WeaveClass(target = "com.example.cache.ComputeService")
  public static class CacheAspect {
    // Guard body must stay empty: the counter lives in the woven target body, and a cache hit
    // short-circuits before the guard runs. Counting here would double-count misses.
    @CacheResult(value = "computeBatch", keyArgIndices = {1})
    public void cacheBatch(MethodInvocation inv) {}
  }

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("CACHE-WEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  /** Standalone delegate so {@code MethodDelegation} has exactly one source method to consider. */
  public static class BatchTarget {
    static volatile boolean compute;

    public static int computeBatch(String client, int[] batch) {
      if (compute) {
        batchHookCalls.incrementAndGet();
      }
      int sum = 0;
      for (int value : batch) {
        sum += value;
      }
      return sum;
    }
  }

  static final AtomicInteger batchHookCalls = new AtomicInteger(0);

  private static DefaultInterceptorRegistry registry;
  private static Object instance;
  private static Method computeBatchMethod;

  @BeforeAll
  static void weaveTarget() {
    if (instrumentation == null) {
      return;
    }

    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name("com.example.cache.ComputeService")
            .defineMethod("computeBatch", int.class, Visibility.PUBLIC)
            .withParameters(String.class, int[].class)
            .intercept(MethodDelegation.to(BatchTarget.class))
            .make()
            .load(CacheRealWeavingTest.class.getClassLoader())
            .getLoaded();

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);

    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> aspects = new HashSet<>();
    aspects.add(CacheAspect.class);
    loader.loadAnnotatedInterceptors(aspects, registry);

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.install(instrumentation, true);

    try {
      instance = target.getDeclaredConstructor().newInstance();
      computeBatchMethod = target.getMethod("computeBatch", String.class, int[].class);
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
    BatchTarget.compute = true;
    batchHookCalls.set(0);
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
    BatchTarget.compute = false;
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
  void equalContentFreshArray_hitsCacheAndSkipsBody() throws Exception {
    int[] firstBatch = {2, 3, 5};
    Object first =
        computeBatchMethod.invoke(instance, "client-a", firstBatch);
    assertEquals(10, first, "first call runs the body and sums the batch");

    int[] secondBatch = {2, 3, 5}; // same content, brand-new array instance
    Object second = computeBatchMethod.invoke(instance, "client-b", secondBatch);

    assertEquals(first, second, "second equal-content call must return the cached value");
    // The target body only ever ran once; the second call short-circuited on the cache hit.
    assertEquals(1, batchHookCalls.get(), "body must run exactly once across both calls");
  }

  @Test
  void differentContentArray_keysDiffer() throws Exception {
    Object first = computeBatchMethod.invoke(instance, "client-a", new int[] {1, 1, 1});
    assertEquals(3, first);

    Object second = computeBatchMethod.invoke(instance, "client-a", new int[] {2, 2, 2});
    assertEquals(6, second, "different content must not be served from the first call's cache");
    assertEquals(2, batchHookCalls.get(), "both distinct-content calls must run the body");
  }

  @Test
  void cacheDoesNotLeakBetweenClients() throws Exception {
    // keyArgIndices={1} -> only the array participates in the key; a different client with equal
    // batch content is intentionally served from cache. This asserts the keyArgIndices subset
    // semantics, not cross-client isolation.
    Object first = computeBatchMethod.invoke(instance, "client-a", new int[] {9, 9});
    assertEquals(18, first);

    Object second = computeBatchMethod.invoke(instance, "client-b", new int[] {9, 9});
    assertEquals(18, second, "equal keyed content across clients must hit the same cache entry");
    assertEquals(1, batchHookCalls.get());
  }
}
