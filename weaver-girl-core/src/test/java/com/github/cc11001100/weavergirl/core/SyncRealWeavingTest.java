package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.annotation.Synchronized;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * End-to-end proof that {@code @Synchronized} really serializes the woven method <em>body</em>,
 * not just the before/after callback windows.
 *
 * <p>The original implementation wrapped only {@code delegate.before}/{@code after} in
 * {@code synchronized} blocks, so the lock was released as soon as the before() frame returned —
 * long before the target method body executed. Two threads could therefore run the body
 * concurrently, and the annotation was effectively a no-op for mutual exclusion. This test weaves a
 * generated class whose {@code work()} sleeps 80 ms per call and records the exact time window of
 * each body run; with {@code @Synchronized} active the windows of two concurrent calls must not
 * overlap.
 */
class SyncRealWeavingTest {

  @WeaveClass(target = "com.example.sync.Worker")
  public static class SyncAspect {
    @Synchronized("work")
    public void syncWork(MethodInvocation inv) {
      // synchronization is applied by the engine; body serialization is what we assert on
    }
  }

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("SYNC-WEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  /**
   * Standalone delegate so {@code MethodDelegation} has exactly one source method to consider.
   */
  public static class WorkTarget {
    /** Records [startNanos, endNanos] of every executed body; sleep keeps the window large. */
    static final List<long[]> windows = new ArrayList<>();
    static final AtomicInteger inBody = new AtomicInteger(0);
    static final AtomicInteger maxConcurrent = new AtomicInteger(0);

    public static void doWork() {
      long start = System.nanoTime();
      int now = inBody.incrementAndGet();
      // Track the highest concurrency inside the body; with @Synchronized it must stay 1.
      for (;;) {
        int seen = maxConcurrent.get();
        if (now <= seen || maxConcurrent.compareAndSet(seen, now)) {
          break;
        }
      }
      try {
        Thread.sleep(80);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      inBody.decrementAndGet();
      windows.add(new long[] {start, System.nanoTime()});
    }
  }

  private static DefaultInterceptorRegistry registry;
  private static Object instance;
  private static Method workMethod;

  @BeforeAll
  static void weaveTarget() {
    if (instrumentation == null) {
      return;
    }

    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name("com.example.sync.Worker")
            .defineMethod("work", void.class, Visibility.PUBLIC)
            .intercept(MethodDelegation.to(WorkTarget.class))
            .make()
            .load(SyncRealWeavingTest.class.getClassLoader())
            .getLoaded();

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);

    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> aspects = new HashSet<>();
    aspects.add(SyncAspect.class);
    loader.loadAnnotatedInterceptors(aspects, registry);

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.install(instrumentation, true);

    try {
      instance = target.getDeclaredConstructor().newInstance();
      workMethod = target.getMethod("work");
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
    WorkTarget.windows.clear();
    WorkTarget.inBody.set(0);
    WorkTarget.maxConcurrent.set(0);
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
  void synchronized_serializesConcurrentMethodBodies() throws Exception {
    int threads = 2;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    List<Thread> runners = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Thread t =
          new Thread(
              () -> {
                try {
                  start.await();
                  workMethod.invoke(instance);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                } finally {
                  done.countDown();
                }
              });
      t.setDaemon(true);
      t.start();
      runners.add(t);
    }
    start.countDown();
    assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));

    // Every body run completed.
    assertEquals(threads, WorkTarget.windows.size(), "both calls must run the body to completion");
    // The body was never entered concurrently: @Synchronized must hold the lock across the sleep.
    assertEquals(1, WorkTarget.maxConcurrent.get(), "body windows must never overlap");
    // The two runs must be sequential: each 80ms window, and the second started after the first ended.
    long[][] sorted =
        WorkTarget.windows.stream()
            .sorted((a, b) -> Long.compare(a[0], b[0]))
            .toArray(long[][]::new);
    assertTrue(sorted[1][0] >= sorted[0][1], "second body run must start after the first ends");
  }
}
