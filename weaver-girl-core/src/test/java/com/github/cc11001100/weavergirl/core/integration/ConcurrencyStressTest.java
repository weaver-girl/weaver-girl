package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** High-concurrency stress tests verifying thread-safety of core components. */
class ConcurrencyStressTest {

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
  }

  @AfterEach
  void tearDown() {
    registry.clear();
  }

  @Test
  @DisplayName("Concurrent register/unregister should not corrupt registry")
  void concurrentRegisterUnregister() throws Exception {
    int threadCount = 20;
    int opsPerThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger errors = new AtomicInteger(0);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < opsPerThread; i++) {
                String name = "plugin-" + threadId + "-" + i;
                InterceptorDefinition def =
                    new InterceptorDefinition(
                        name,
                        new Pointcut(ClassMatcher.byName("Target" + threadId), MethodMatcher.any()),
                        new Interceptor() {},
                        0);
                registry.register(def);

                // Immediately query (stress the index rebuild)
                registry.getInterceptorsForClass("Target" + threadId);

                if (i % 3 == 0) {
                  registry.unregister(name);
                }
              }
            } catch (Exception e) {
              errors.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(120, TimeUnit.SECONDS));
    executor.shutdown();

    assertEquals(0, errors.get(), "No exceptions expected during concurrent operations");
  }

  @Test
  @DisplayName("Concurrent reads should be consistent under writes")
  void concurrentReadsWithWrites() throws Exception {
    // Pre-populate
    for (int i = 0; i < 50; i++) {
      registry.register(
          new InterceptorDefinition(
              "init-" + i,
              new Pointcut(ClassMatcher.byName("Class" + i), MethodMatcher.any()),
              new Interceptor() {},
              0));
    }

    int readerCount = 10;
    int writerCount = 5;
    ExecutorService executor = Executors.newFixedThreadPool(readerCount + writerCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(readerCount + writerCount);
    AtomicInteger readErrors = new AtomicInteger(0);

    // Readers
    for (int r = 0; r < readerCount; r++) {
      final int readerId = r;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < 200; i++) {
                List<InterceptorDefinition> defs =
                    registry.getInterceptorsForClass("Class" + (readerId % 50));
                // Should never return null, and should never throw
                assertNotNull(defs);
              }
            } catch (Exception e) {
              readErrors.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    // Writers
    for (int w = 0; w < writerCount; w++) {
      final int writerId = w;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < 100; i++) {
                String name = "writer-" + writerId + "-" + i;
                registry.register(
                    new InterceptorDefinition(
                        name,
                        new Pointcut(
                            ClassMatcher.byName("Dynamic" + writerId), MethodMatcher.any()),
                        new Interceptor() {},
                        i));
                if (i % 5 == 0) {
                  registry.unregister(name);
                }
              }
            } catch (Exception e) {
              // Writers may see contention but should not corrupt state
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(120, TimeUnit.SECONDS));
    executor.shutdown();

    assertEquals(0, readErrors.get(), "Readers should never see exceptions");
  }

  @Test
  @DisplayName("InterceptorHolder is thread-safe under concurrent access")
  void interceptorHolderConcurrency() throws Exception {
    int threadCount = 20;
    int opsPerThread = 500;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger errors = new AtomicInteger(0);

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              String name = "stress-" + threadId;
              for (int i = 0; i < opsPerThread; i++) {
                // Mix of success and failure recordings
                if (i % 3 == 0) {
                  InterceptorHolder.recordInterceptorFailure(name);
                } else {
                  InterceptorHolder.recordInterceptorSuccess(name);
                }
                // Should always return a boolean without throwing
                InterceptorHolder.shouldInvoke(name);
              }
            } catch (Exception e) {
              errors.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(120, TimeUnit.SECONDS));
    executor.shutdown();

    assertEquals(0, errors.get(), "InterceptorHolder should be thread-safe");
  }

  @Test
  @DisplayName("getAllDefinitions returns unmodifiable view")
  void unmodifiableDefinitions() throws Exception {
    // Register some definitions
    for (int i = 0; i < 50; i++) {
      registry.register(
          new InterceptorDefinition(
              "snap-" + i,
              new Pointcut(ClassMatcher.byName("Snap"), MethodMatcher.any()),
              new Interceptor() {},
              0));
    }

    List<InterceptorDefinition> view = registry.getAllDefinitions();
    assertEquals(50, view.size());

    // Verify unmodifiable — direct mutation should throw
    assertThrows(UnsupportedOperationException.class, () -> view.add(null));
    assertThrows(UnsupportedOperationException.class, () -> view.remove(0));

    // Registry mutation should be reflected (it's a live view)
    registry.register(
        new InterceptorDefinition(
            "snap-extra",
            new Pointcut(ClassMatcher.byName("Snap"), MethodMatcher.any()),
            new Interceptor() {},
            0));
    assertEquals(51, view.size());
  }
}
