package com.github.cc11001100.weavergirl.plugins.async;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

/**
 * End-to-end integration test for {@link AsyncContextPropagationPlugin}.
 *
 * <p>Installs a real ByteBuddy transformer (via self-attach) and verifies that a plain {@code
 * Runnable} / {@code Callable} submitted to a real {@link ExecutorService} has its {@link
 * ThreadContext} automatically propagated into the worker thread — with no manual wrapping by the
 * application.
 *
 * <p>This is the test that proves the P0-4 feature works through the full advice pipeline:
 * interface matching → {@link com.github.cc11001100.weavergirl.core.AsyncArgumentAdvice} (which
 * binds {@code @Advice.Argument(0, readOnly=false)}) → argument wrap at submit time → context
 * restore in the worker thread.
 *
 * <h3>Why a custom ExecutorService, not {@code Executors.newSingleThreadExecutor()}?</h3>
 *
 * <p>{@code ThreadPoolExecutor} and the {@code Executors} delegation wrappers live on the
 * <em>bootstrap</em> classloader. Advice inlined into a bootstrap class cannot resolve the agent's
 * own classes ({@code InterceptorHolder}, {@code ContextRunnable}, …) unless the agent jar is
 * appended to the bootstrap classloader — which this agent deliberately does not do (it caused a
 * loader-constraint split with ByteBuddy, see {@code WeaverTransformer.install}).
 * Application-defined executors (Spring's {@code ThreadPoolTaskExecutor}, custom {@code
 * AbstractExecutorService} subclasses, etc.) live on the app/system classloader and can see the
 * agent classes, so they <em>are</em> interceptable. {@link InAppExecutor} below is a minimal such
 * executor.
 */
class AsyncContextPropagationIntegrationTest {

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
    Assumptions.assumeTrue(
        instrumentation != null, "ByteBuddyAgent self-attach not available in this environment");
    Assumptions.assumeTrue(
        instrumentation.isRetransformClassesSupported(),
        "JVM does not support class retransformation");

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    ThreadContext.clear();
  }

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
    InterceptorHolder.setRegistry(null);
  }

  @Test
  void execute_propagatesContextAutomatically() throws Exception {
    installPlugin();

    ThreadContext.put("traceId", "trace-execute");

    InAppExecutor executor = new InAppExecutor();
    AtomicReference<String> workerTraceId = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try {
      executor.execute(
          () -> {
            workerTraceId.set(ThreadContext.get("traceId"));
            latch.countDown();
          });
      // Clear AFTER submit — capture happens inside before() at submit time.
      // The worker must see the value via propagation, not residual thread-local.
      ThreadContext.clear();

      assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker task should complete");
    } finally {
      executor.shutdownNow();
    }

    assertEquals(
        "trace-execute",
        workerTraceId.get(),
        "ThreadContext should be auto-propagated to the worker thread via execute(Runnable)");
  }

  @Test
  void submitRunnable_propagatesContextAutomatically() throws Exception {
    installPlugin();

    ThreadContext.put("traceId", "trace-submit-runnable");

    InAppExecutor executor = new InAppExecutor();
    AtomicReference<String> workerTraceId = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try {
      executor.submit(
          () -> {
            workerTraceId.set(ThreadContext.get("traceId"));
            latch.countDown();
          });
      ThreadContext.clear();

      assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker task should complete");
    } finally {
      executor.shutdownNow();
    }

    assertEquals(
        "trace-submit-runnable",
        workerTraceId.get(),
        "ThreadContext should be auto-propagated to the worker thread via submit(Runnable)");
  }

  @Test
  void submitCallable_propagatesContextAutomatically() throws Exception {
    installPlugin();

    ThreadContext.put("traceId", "trace-submit-callable");

    InAppExecutor executor = new InAppExecutor();
    try {
      Future<String> future = executor.submit(() -> ThreadContext.get("traceId"));
      ThreadContext.clear();

      String result = future.get(5, TimeUnit.SECONDS);
      assertEquals(
          "trace-submit-callable",
          result,
          "ThreadContext should be auto-propagated to the worker thread via submit(Callable)");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void contextDoesNotLeakBetweenTasks() throws Exception {
    installPlugin();

    InAppExecutor executor = new InAppExecutor();

    // First task carries a traceId; the worker thread runs it under the
    // propagated context, then ContextRunnable restores the worker's prior
    // (empty) context in its finally block.
    ThreadContext.put("traceId", "first-task");
    CountDownLatch firstDone = new CountDownLatch(1);
    executor.submit((Runnable) firstDone::countDown);
    ThreadContext.clear();
    assertTrue(firstDone.await(5, TimeUnit.SECONDS), "First task should complete");

    // Second task is submitted with NO context on the submitting thread.
    // The worker must see an empty context — not the first task's traceId.
    AtomicReference<String> leaked = new AtomicReference<>("sentinel");
    CountDownLatch secondDone = new CountDownLatch(1);
    executor.submit(
        () -> {
          leaked.set(ThreadContext.get("traceId"));
          secondDone.countDown();
        });
    assertTrue(secondDone.await(5, TimeUnit.SECONDS), "Second task should complete");

    try {
      assertNull(
          leaked.get(),
          "Worker thread context should not leak between tasks — "
              + "ContextRunnable must restore the worker's original (empty) context");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void scheduleRunnable_propagatesContextAutomatically() throws Exception {
    installPlugin();

    ThreadContext.put("traceId", "trace-schedule-runnable");

    InAppScheduledExecutor executor = new InAppScheduledExecutor();
    AtomicReference<String> workerTraceId = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try {
      executor.schedule(
          () -> {
            workerTraceId.set(ThreadContext.get("traceId"));
            latch.countDown();
          },
          1,
          TimeUnit.MILLISECONDS);
      ThreadContext.clear();

      assertTrue(latch.await(5, TimeUnit.SECONDS), "Scheduled worker task should complete");
    } finally {
      executor.shutdownNow();
    }

    assertEquals(
        "trace-schedule-runnable",
        workerTraceId.get(),
        "ThreadContext should be auto-propagated via schedule(Runnable,long,TimeUnit)");
  }

  @Test
  void scheduleCallable_propagatesContextAutomatically() throws Exception {
    installPlugin();

    ThreadContext.put("traceId", "trace-schedule-callable");

    InAppScheduledExecutor executor = new InAppScheduledExecutor();
    try {
      ScheduledFuture<String> future =
          executor.schedule(() -> ThreadContext.get("traceId"), 1, TimeUnit.MILLISECONDS);
      ThreadContext.clear();

      assertEquals(
          "trace-schedule-callable",
          future.get(5, TimeUnit.SECONDS),
          "ThreadContext should be auto-propagated via schedule(Callable,long,TimeUnit)");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void scheduleAtFixedRate_propagatesContextAutomatically() throws Exception {
    installPlugin();

    ThreadContext.put("traceId", "trace-fixed-rate");

    InAppScheduledExecutor executor = new InAppScheduledExecutor();
    AtomicReference<String> workerTraceId = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(2);

    ScheduledFuture<?> future = null;
    try {
      future =
          executor.scheduleAtFixedRate(
              () -> {
                workerTraceId.set(ThreadContext.get("traceId"));
                latch.countDown();
              },
              1,
              1,
              TimeUnit.MILLISECONDS);
      ThreadContext.clear();

      assertTrue(latch.await(5, TimeUnit.SECONDS), "Periodic scheduled task should run twice");
    } finally {
      if (future != null) {
        future.cancel(true);
      }
      executor.shutdownNow();
    }

    assertEquals(
        "trace-fixed-rate",
        workerTraceId.get(),
        "ThreadContext should be auto-propagated via"
            + " scheduleAtFixedRate(Runnable,long,long,TimeUnit)");
  }

  @Test
  void scheduleWithFixedDelay_propagatesContextAutomatically() throws Exception {
    installPlugin();

    ThreadContext.put("traceId", "trace-fixed-delay");

    InAppScheduledExecutor executor = new InAppScheduledExecutor();
    AtomicReference<String> workerTraceId = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(2);

    ScheduledFuture<?> future = null;
    try {
      future =
          executor.scheduleWithFixedDelay(
              () -> {
                workerTraceId.set(ThreadContext.get("traceId"));
                latch.countDown();
              },
              1,
              1,
              TimeUnit.MILLISECONDS);
      ThreadContext.clear();

      assertTrue(latch.await(5, TimeUnit.SECONDS), "Fixed-delay scheduled task should run twice");
    } finally {
      if (future != null) {
        future.cancel(true);
      }
      executor.shutdownNow();
    }

    assertEquals(
        "trace-fixed-delay",
        workerTraceId.get(),
        "ThreadContext should be auto-propagated via"
            + " scheduleWithFixedDelay(Runnable,long,long,TimeUnit)");
  }

  private void installPlugin() {
    AsyncContextPropagationPlugin plugin = new AsyncContextPropagationPlugin();
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation);
    // InAppExecutor may already be loaded by the time install() runs (the
    // test class references it); retransform explicitly so already-loaded
    // Executor implementors on the app classloader get the advice applied.
    transformer.retransformLoadedClasses();
  }

  /**
   * Minimal {@link ExecutorService} on the app classloader. {@code submit} is inherited from {@link
   * AbstractExecutorService} and internally calls {@code execute}, so hooking {@code execute}
   * covers both paths.
   */
  static final class InAppExecutor extends AbstractExecutorService {
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private volatile boolean stopped = false;

    InAppExecutor() {
      Thread worker =
          new Thread(
              () -> {
                while (!stopped) {
                  try {
                    Runnable task = queue.take();
                    task.run();
                  } catch (InterruptedException e) {
                    break;
                  }
                }
              },
              "InAppExecutor-worker");
      worker.setDaemon(true);
      worker.start();
    }

    @Override
    public void execute(Runnable command) {
      queue.add(command);
    }

    @Override
    public void shutdown() {
      stopped = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      stopped = true;
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return stopped;
    }

    @Override
    public boolean isTerminated() {
      return stopped;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  /**
   * Minimal {@link ScheduledExecutorService} on the app classloader. It does not model real timing;
   * tests only need a concrete scheduled executor whose schedule methods can be transformed and
   * whose first task argument is run later on the worker thread.
   */
  static final class InAppScheduledExecutor extends AbstractExecutorService
      implements ScheduledExecutorService {
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private volatile boolean stopped = false;

    InAppScheduledExecutor() {
      Thread worker =
          new Thread(
              () -> {
                while (!stopped) {
                  try {
                    queue.take().run();
                  } catch (InterruptedException e) {
                    break;
                  }
                }
              },
              "InAppScheduledExecutor-worker");
      worker.setDaemon(true);
      worker.start();
    }

    @Override
    public void execute(Runnable command) {
      queue.add(command);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      SimpleScheduledFuture<Void> future =
          new SimpleScheduledFuture<>(Executors.callable(command, null));
      queue.add(future);
      return future;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      SimpleScheduledFuture<V> future = new SimpleScheduledFuture<>(callable);
      queue.add(future);
      return future;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      PeriodicScheduledFuture future = new PeriodicScheduledFuture(command, period, unit);
      queue.add(future);
      return future;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      PeriodicScheduledFuture future = new PeriodicScheduledFuture(command, delay, unit);
      queue.add(future);
      return future;
    }

    @Override
    public void shutdown() {
      stopped = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      stopped = true;
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return stopped;
    }

    @Override
    public boolean isTerminated() {
      return stopped;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  static final class SimpleScheduledFuture<V> extends FutureTask<V> implements ScheduledFuture<V> {
    SimpleScheduledFuture(Callable<V> callable) {
      super(callable);
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(java.util.concurrent.Delayed other) {
      return 0;
    }
  }

  static final class PeriodicScheduledFuture implements ScheduledFuture<Object>, Runnable {
    private final Runnable command;
    private final long delayMillis;
    private volatile boolean cancelled;
    private volatile boolean done;
    private volatile Thread runner;

    PeriodicScheduledFuture(Runnable command, long delay, TimeUnit unit) {
      this.command = command;
      this.delayMillis = Math.max(1L, unit.toMillis(delay));
    }

    @Override
    public void run() {
      runner = Thread.currentThread();
      try {
        while (!cancelled) {
          command.run();
          Thread.sleep(delayMillis);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        done = true;
      }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelled = true;
      if (mayInterruptIfRunning && runner != null) {
        runner.interrupt();
      }
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(java.util.concurrent.Delayed other) {
      return 0;
    }
  }

  private static class StubContext implements PluginContext {
    private final Map<String, String> config;

    StubContext(Map<String, String> config) {
      this.config = config;
    }

    @Override
    public InterceptorRegistry getRegistry() {
      return null;
    }

    @Override
    public String getConfig(String key) {
      return config.get(key);
    }

    @Override
    public String getConfig(String key, String defaultValue) {
      return config.getOrDefault(key, defaultValue);
    }

    @Override
    public Map<String, String> getAllConfig() {
      return config;
    }

    @Override
    public String getPluginName() {
      return "async-context-propagation";
    }
  }
}
