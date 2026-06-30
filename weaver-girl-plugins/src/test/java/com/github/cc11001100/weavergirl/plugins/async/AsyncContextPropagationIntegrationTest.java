package com.github.cc11001100.weavergirl.plugins.async;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link AsyncContextPropagationPlugin}.
 *
 * <p>Installs a real ByteBuddy transformer (via self-attach) and verifies that a
 * plain {@code Runnable} / {@code Callable} submitted to a real
 * {@link ExecutorService} has its {@link ThreadContext} automatically propagated
 * into the worker thread — with no manual wrapping by the application.</p>
 *
 * <p>This is the test that proves the P0-4 feature works through the full advice
 * pipeline: interface matching → {@link com.github.cc11001100.weavergirl.core.AsyncArgumentAdvice}
 * (which binds {@code @Advice.Argument(0, readOnly=false)}) → argument wrap at
 * submit time → context restore in the worker thread.</p>
 *
 * <h3>Why a custom ExecutorService, not {@code Executors.newSingleThreadExecutor()}?</h3>
 * <p>{@code ThreadPoolExecutor} and the {@code Executors} delegation wrappers live
 * on the <em>bootstrap</em> classloader. Advice inlined into a bootstrap class
 * cannot resolve the agent's own classes ({@code InterceptorHolder},
 * {@code ContextRunnable}, …) unless the agent jar is appended to the bootstrap
 * classloader — which this agent deliberately does not do (it caused a
 * loader-constraint split with ByteBuddy, see {@code WeaverTransformer.install}).
 * Application-defined executors (Spring's {@code ThreadPoolTaskExecutor}, custom
 * {@code AbstractExecutorService} subclasses, etc.) live on the app/system
 * classloader and can see the agent classes, so they <em>are</em> interceptable.
 * {@link InAppExecutor} below is a minimal such executor.</p>
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
        Assumptions.assumeTrue(instrumentation != null,
                "ByteBuddyAgent self-attach not available in this environment");
        Assumptions.assumeTrue(instrumentation.isRetransformClassesSupported(),
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
            executor.execute(() -> {
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

        assertEquals("trace-execute", workerTraceId.get(),
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
            executor.submit(() -> {
                workerTraceId.set(ThreadContext.get("traceId"));
                latch.countDown();
            });
            ThreadContext.clear();

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker task should complete");
        } finally {
            executor.shutdownNow();
        }

        assertEquals("trace-submit-runnable", workerTraceId.get(),
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
            assertEquals("trace-submit-callable", result,
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
        executor.submit(() -> {
            leaked.set(ThreadContext.get("traceId"));
            secondDone.countDown();
        });
        assertTrue(secondDone.await(5, TimeUnit.SECONDS), "Second task should complete");

        try {
            assertNull(leaked.get(),
                    "Worker thread context should not leak between tasks — "
                            + "ContextRunnable must restore the worker's original (empty) context");
        } finally {
            executor.shutdownNow();
        }
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
     * Minimal {@link ExecutorService} on the app classloader. {@code submit}
     * is inherited from {@link AbstractExecutorService} and internally calls
     * {@code execute}, so hooking {@code execute} covers both paths.
     */
    static final class InAppExecutor extends AbstractExecutorService {
        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private volatile boolean stopped = false;

        InAppExecutor() {
            Thread worker = new Thread(() -> {
                while (!stopped) {
                    try {
                        Runnable task = queue.take();
                        task.run();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "InAppExecutor-worker");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        @Override
        public void shutdown() { stopped = true; }

        @Override
        public List<Runnable> shutdownNow() {
            stopped = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() { return stopped; }

        @Override
        public boolean isTerminated() { return stopped; }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private static class StubContext implements PluginContext {
        private final Map<String, String> config;

        StubContext(Map<String, String> config) {
            this.config = config;
        }

        @Override
        public InterceptorRegistry getRegistry() { return null; }

        @Override
        public String getConfig(String key) { return config.get(key); }

        @Override
        public String getConfig(String key, String defaultValue) {
            return config.getOrDefault(key, defaultValue);
        }

        @Override
        public Map<String, String> getAllConfig() { return config; }

        @Override
        public String getPluginName() { return "async-context-propagation"; }
    }
}
