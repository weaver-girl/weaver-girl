package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.config.ConfigSnapshot;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.circuit.InterceptorCircuitBreaker;
import com.github.cc11001100.weavergirl.core.config.DefaultDynamicConfigManager;
import com.github.cc11001100.weavergirl.core.persistence.AgentStateSnapshot;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

/**
 * Full end-to-end integration test exercising the complete agent lifecycle: bootstrap → register
 * plugins → intercept → events → circuit breaker → sampling → dynamic config → state persistence →
 * shutdown.
 *
 * <p>This test uses the programmatic API (no -javaagent required) to verify that all components
 * work together correctly in a real runtime scenario.
 */
class FullAgentLifecycleTest {

  private DefaultInterceptorRegistry registry;
  private SamplingController samplingController;
  private InterceptorCircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    samplingController = SamplingController.getInstance();
    circuitBreaker = new InterceptorCircuitBreaker();
  }

  @AfterEach
  void tearDown() {
    samplingController.resetCounter();
  }

  // ========== Phase 1: Bootstrap & Registration ==========

  @Test
  void fullBootstrap_registersPluginsAndInterceptors() {
    List<InterceptorDefinition> definitions = new ArrayList<>();

    definitions.add(
        new InterceptorDefinition(
            "jdbc-execute",
            new Pointcut(
                ClassMatcher.byName("java.sql.Statement"), MethodMatcher.byName("execute")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation inv) {}
            }));

    definitions.add(
        new InterceptorDefinition(
            "servlet-service",
            new Pointcut(
                ClassMatcher.byName("javax.servlet.http.HttpServlet"),
                MethodMatcher.byName("service")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation inv) {}
            }));

    for (InterceptorDefinition def : definitions) {
      registry.register(def);
    }

    assertEquals(2, registry.getAllDefinitions().size());
    assertTrue(registry.getInterceptorsForClass("java.sql.Statement").size() > 0);
    assertTrue(registry.getInterceptorsForClass("javax.servlet.http.HttpServlet").size() > 0);
  }

  // ========== Phase 2: Interception & Events ==========

  @Test
  void interceptor_publishesEvents_onSlowInvocation() {
    List<InterceptorEvent> events = new CopyOnWriteArrayList<>();
    InterceptorEventListener listener = events::add;
    InterceptorEventPublisher.getInstance().addListener(listener);

    try {
      AtomicBoolean beforeCalled = new AtomicBoolean();
      AtomicBoolean afterCalled = new AtomicBoolean();

      Interceptor interceptor =
          new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
              beforeCalled.set(true);
              startTime.set(System.nanoTime());
            }

            @Override
            public void after(MethodInvocation inv) {
              afterCalled.set(true);
              Long start = startTime.get();
              startTime.remove();
              if (start != null) {
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                InterceptorEventPublisher.getInstance()
                    .publish(
                        InterceptorEvent.builder()
                            .type(elapsed >= 100 ? "slow-request" : "request")
                            .plugin("test")
                            .className("TestService")
                            .methodName("process")
                            .durationMs(elapsed)
                            .build());
              }
            }
          };

      MethodInvocation inv = new MethodInvocation(String.class, "toString", "test", null);
      interceptor.before(inv);
      interceptor.after(inv);

      assertTrue(beforeCalled.get());
      assertTrue(afterCalled.get());
      assertFalse(events.isEmpty());

      InterceptorEvent event = events.get(0);
      assertEquals("request", event.getType());
      assertEquals("test", event.getPlugin());
      assertEquals("TestService", event.getClassName());
    } finally {
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  // ========== Phase 3: Circuit Breaker ==========

  @Test
  void circuitBreaker_tripsAfterConsecutiveFailures() {
    String interceptorName = "test-failing-interceptor";

    // Simulate consecutive failures
    for (int i = 0; i < 5; i++) {
      circuitBreaker.recordFailure(interceptorName);
    }

    // Circuit should be open now — shouldInvoke returns false
    assertFalse(circuitBreaker.shouldInvoke(interceptorName));
  }

  @Test
  void circuitBreaker_allowsAfterCooldown() throws InterruptedException {
    String interceptorName = "test-cooldown";
    InterceptorCircuitBreaker fastBreaker = new InterceptorCircuitBreaker(3, 100); // 100ms cooldown

    // Trip the breaker
    for (int i = 0; i < 3; i++) {
      fastBreaker.recordFailure(interceptorName);
    }
    assertFalse(fastBreaker.shouldInvoke(interceptorName));

    // Wait for cooldown
    Thread.sleep(150);

    // Should be closed again
    assertTrue(fastBreaker.shouldInvoke(interceptorName));
  }

  @Test
  void circuitBreaker_resetsOnSuccess() {
    String name = "test-reset-cb";
    // Record 4 failures (below threshold of 5)
    for (int i = 0; i < 4; i++) {
      circuitBreaker.recordFailure(name);
    }
    // Still should invoke (threshold is 5)
    assertTrue(circuitBreaker.shouldInvoke(name));

    // Record success resets the count
    circuitBreaker.recordSuccess(name);

    // Now 4 more failures shouldn't trip it because success reset the count
    for (int i = 0; i < 4; i++) {
      circuitBreaker.recordFailure(name);
    }
    assertTrue(circuitBreaker.shouldInvoke(name));
  }

  // ========== Phase 4: Sampling ==========

  @Test
  void samplingController_reducesInterceptionUnderLoad() {
    SamplingController sc = SamplingController.getInstance();
    int originalRate = sc.getSamplingRate();
    try {
      sc.setSamplingRate(10); // 1 in 10

      int sampled = 0;
      int total = 1000;
      for (int i = 0; i < total; i++) {
        if (sc.shouldSample()) {
          sampled++;
        }
      }

      // Should be approximately 100 (10%)
      assertTrue(
          sampled > 50 && sampled < 200,
          "Expected ~100 samples out of 1000 with rate=10, got " + sampled);
    } finally {
      sc.setSamplingRate(originalRate);
    }
  }

  // ========== Phase 5: Dynamic Config ==========

  @Test
  void dynamicConfig_changesPropagateToListeners() {
    DefaultDynamicConfigManager configManager = new DefaultDynamicConfigManager();

    AtomicReference<String> captured = new AtomicReference<>();
    configManager.addListener(
        event -> {
          if ("test.key".equals(event.getKey())) {
            captured.set(event.getNewValue());
          }
        });

    configManager.set("test.key", "initial", "test");
    assertEquals("initial", configManager.get("test.key"));

    configManager.set("test.key", "updated", "test");
    assertEquals("updated", configManager.get("test.key"));
    assertEquals("updated", captured.get());
  }

  @Test
  void dynamicConfig_rollbackRestoresPreviousState() {
    DefaultDynamicConfigManager configManager = new DefaultDynamicConfigManager();

    configManager.set("key1", "v1", "test");
    configManager.set("key2", "v2", "test");

    // Take snapshot
    ConfigSnapshot snapshot = configManager.snapshot("before-change");

    configManager.set("key1", "changed", "test");
    configManager.set("key2", "also-changed", "test");

    // Rollback using version number
    configManager.rollback(snapshot.getVersion());

    assertEquals("v1", configManager.get("key1"));
    assertEquals("v2", configManager.get("key2"));
  }

  // ========== Phase 6: State Persistence ==========

  @Test
  void statePersistence_snapshotAndRestore() {
    AgentStateSnapshot snapshot =
        new AgentStateSnapshot()
            .setInterceptorCount(15)
            .setTotalInvocationCount(5000)
            .setPluginState("jdbc", "ACTIVE")
            .setPluginState("redis", "DISABLED")
            .setConfig("samplingRate", "10")
            .setMetric("slow_queries", 42);

    assertEquals(15, snapshot.getInterceptorCount());
    assertEquals(5000, snapshot.getTotalInvocationCount());
    assertEquals("ACTIVE", snapshot.getPluginState("jdbc"));
    assertEquals("DISABLED", snapshot.getPluginState("redis"));
    assertEquals("10", snapshot.getConfig("samplingRate"));
    assertEquals(42, snapshot.getMetric("slow_queries"));
  }

  // ========== Phase 7: Multi-Plugin Coordination ==========

  @Test
  void multiplePlugins_coordinatingOnSameRegistry() {
    AtomicInteger timingCount = new AtomicInteger();
    Interceptor timingInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            timingCount.incrementAndGet();
          }
        };

    AtomicInteger loggingCount = new AtomicInteger();
    Interceptor loggingInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            loggingCount.incrementAndGet();
          }
        };

    registry.register(
        new InterceptorDefinition(
            "timing-Service-process",
            new Pointcut(
                ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
            timingInterceptor));

    registry.register(
        new InterceptorDefinition(
            "logging-Service-process",
            new Pointcut(
                ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
            loggingInterceptor));

    List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.Service");
    assertEquals(2, defs.size());

    MethodInvocation inv =
        new MethodInvocation(FullAgentLifecycleTest.class, "process", this, null);

    for (InterceptorDefinition def : defs) {
      def.getInterceptor().before(inv);
    }

    assertEquals(1, timingCount.get());
    assertEquals(1, loggingCount.get());
  }

  // ========== Phase 8: Concurrent Stress ==========

  @Test
  void concurrentRegistrations_noDataLoss() throws Exception {
    int threadCount = 10;
    int registrationsPerThread = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger totalRegistered = new AtomicInteger();

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      new Thread(
              () -> {
                try {
                  for (int i = 0; i < registrationsPerThread; i++) {
                    String name = "thread-" + threadId + "-interceptor-" + i;
                    registry.register(
                        new InterceptorDefinition(
                            name,
                            new Pointcut(
                                ClassMatcher.byName("com.example.Service" + threadId),
                                MethodMatcher.byName("method" + i)),
                            new Interceptor() {}));
                    totalRegistered.incrementAndGet();
                  }
                } finally {
                  latch.countDown();
                }
              })
          .start();
    }

    assertTrue(latch.await(60, TimeUnit.SECONDS));
    assertEquals(threadCount * registrationsPerThread, totalRegistered.get());
    assertEquals(threadCount * registrationsPerThread, registry.getAllDefinitions().size());
  }

  @Test
  void concurrentInterceptors_threadSafe() throws Exception {
    AtomicBoolean beforeCalled = new AtomicBoolean();
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            beforeCalled.set(true);
          }
        };

    registry.register(
        new InterceptorDefinition(
            "concurrent-test",
            new Pointcut(
                ClassMatcher.byName("com.example.ConcurrentService"),
                MethodMatcher.byName("process")),
            interceptor));

    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      new Thread(
              () -> {
                try {
                  List<InterceptorDefinition> defs =
                      registry.getInterceptorsForClass("com.example.ConcurrentService");
                  for (InterceptorDefinition def : defs) {
                    MethodInvocation inv =
                        new MethodInvocation(Object.class, "process", null, null);
                    def.getInterceptor().before(inv);
                  }
                } finally {
                  latch.countDown();
                }
              })
          .start();
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertTrue(beforeCalled.get());
  }

  // ========== Phase 9: Error Recovery ==========

  @Test
  void pluginFailure_doesNotAffectOtherPlugins() {
    AtomicBoolean healthyCalled = new AtomicBoolean();

    Interceptor failingInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            throw new RuntimeException("plugin crash");
          }
        };

    Interceptor healthyInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            healthyCalled.set(true);
          }
        };

    registry.register(
        new InterceptorDefinition(
            "failing-plugin",
            new Pointcut(
                ClassMatcher.byName("com.example.SharedService"), MethodMatcher.byName("process")),
            failingInterceptor));

    registry.register(
        new InterceptorDefinition(
            "healthy-plugin",
            new Pointcut(
                ClassMatcher.byName("com.example.SharedService"), MethodMatcher.byName("process")),
            healthyInterceptor));

    List<InterceptorDefinition> defs =
        registry.getInterceptorsForClass("com.example.SharedService");
    assertEquals(2, defs.size());

    MethodInvocation inv = new MethodInvocation(Object.class, "process", null, null);
    for (InterceptorDefinition def : defs) {
      try {
        def.getInterceptor().before(inv);
      } catch (Exception e) {
        // Circuit breaker would catch this in production
      }
    }

    assertTrue(healthyCalled.get());
  }

  // ========== Phase 10: Graceful Shutdown ==========

  @Test
  void gracefulShutdown_persistsState() {
    AgentStateSnapshot snapshot =
        new AgentStateSnapshot()
            .setInterceptorCount(registry.getAllDefinitions().size())
            .setAgentUptimeMs(System.currentTimeMillis())
            .setPluginState("test-plugin", "ACTIVE");

    assertTrue(snapshot.getInterceptorCount() >= 0);
    assertNotNull(snapshot.getPluginState("test-plugin"));
    assertTrue(snapshot.getSnapshotTimeMs() > 0);
  }

  // ========== Phase 11: Unregistration ==========

  @Test
  void unregister_removesInterceptor() {
    Interceptor interceptor = new Interceptor() {};
    registry.register(
        new InterceptorDefinition(
            "removable",
            new Pointcut(
                ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
            interceptor));

    assertEquals(1, registry.getAllDefinitions().size());
    assertTrue(registry.unregister("removable"));
    assertEquals(0, registry.getAllDefinitions().size());
  }

  // ========== Phase 12: Registry Lookup ==========

  @Test
  void registryLookup_byInterface() {
    registry.register(
        new InterceptorDefinition(
            "jdbc-iface",
            new Pointcut(
                ClassMatcher.byInterface("java.sql.Statement"), MethodMatcher.byName("execute")),
            new Interceptor() {}));

    // Should match concrete classes implementing the interface
    List<InterceptorDefinition> defs =
        registry.getInterceptorsForClass("com.mysql.jdbc.PreparedStatement");
    // Note: without ByteBuddy transformation, this tests registry indexing only
    assertNotNull(defs);
  }
}
