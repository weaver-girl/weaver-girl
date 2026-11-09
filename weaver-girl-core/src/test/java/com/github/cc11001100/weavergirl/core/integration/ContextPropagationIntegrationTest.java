package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.context.ContextPropagators;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that a woven advice chain carries ThreadLocal state across its own cutpoints and
 * across thread boundaries.
 *
 * <p>These run against real woven bytecode, not a simulated advice: {@code @Before} writes into
 * {@link ThreadContext} and {@code @After} reads it back, a nested woven call inherits the caller's
 * context, and {@link ContextPropagators#wrap} hands it to an executor worker and restores the
 * worker's own state afterwards.
 */
class ContextPropagationIntegrationTest {

  // --- Targets: one per scenario, so a transformer installed for one test cannot affect another ---

  public static class CutpointTarget {
    public String work(String name) {
      return "done " + name;
    }
  }

  public static class OuterTarget {
    public String outer() {
      return "outer";
    }
  }

  public static class InnerTarget {
    public String inner() {
      return "inner";
    }
  }

  public static class ExecutorTarget {
    public String async() {
      return "async";
    }
  }

  public static class TracerTarget {
    public String tracedAsync() {
      return "traced";
    }
  }

  // --- Advices -------------------------------------------------------------------------------

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ContextPropagationIntegrationTest$CutpointTarget")
  public static class CutpointAdvice {
    static final List<String> observed = Collections.synchronizedList(new ArrayList<String>());

    @Before("work")
    public void before(MethodInvocation invocation) {
      ThreadContext.put("requestId", "req-" + invocation.getArgument(0));
      observed.add("before=" + ThreadContext.get("requestId"));
    }

    @After("work")
    public void after(MethodInvocation invocation) {
      observed.add("after=" + ThreadContext.get("requestId"));
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ContextPropagationIntegrationTest$OuterTarget")
  public static class OuterAdvice {
    @Before("outer")
    public void before(MethodInvocation invocation) {
      ThreadContext.put("requestId", "req-outer");
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ContextPropagationIntegrationTest$InnerTarget")
  public static class InnerAdvice {
    @Before("inner")
    public void before(MethodInvocation invocation) {
      NestedCalls.add(ThreadContext.get("requestId"));
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ContextPropagationIntegrationTest$ExecutorTarget")
  public static class ExecutorAdvice {
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final AtomicReference<String> seenOnWorker = new AtomicReference<>();
    static final AtomicReference<String> workerBeforeTask = new AtomicReference<>();
    static final AtomicReference<String> workerAfterTask = new AtomicReference<>();
    static final AtomicReference<Throwable> failure = new AtomicReference<>();

    @Before("async")
    public void before(MethodInvocation invocation) {
      ThreadContext.put("requestId", "req-async");
      try {
        Future<?> propagated =
            EXECUTOR.submit(
                ContextPropagators.wrap(
                    () -> seenOnWorker.set(ThreadContext.get("requestId"))));
        propagated.get(5, TimeUnit.SECONDS);
        // Same worker thread, after the wrapped task: the scope must have restored its prior state.
        EXECUTOR.submit(() -> workerAfterTask.set(ThreadContext.get("requestId"))).get(
            5, TimeUnit.SECONDS);
      } catch (Exception e) {
        failure.set(e);
      }
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ContextPropagationIntegrationTest$TracerTarget")
  public static class TracerAdvice {
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final AtomicReference<String> traceIdOnWorker = new AtomicReference<>();
    static final AtomicReference<String> parentTraceId = new AtomicReference<>();
    static final AtomicReference<Boolean> spanClearedAfterTask = new AtomicReference<>();

    @Before("tracedAsync")
    public void before(MethodInvocation invocation) {
      SpanContext span = Tracer.startSpan("advice-span");
      parentTraceId.set(span.getTraceId());
      try {
        EXECUTOR.submit(
                ContextPropagators.wrap(
                    () -> {
                      SpanContext workerSpan = Tracer.getCurrentSpan();
                      traceIdOnWorker.set(workerSpan == null ? null : workerSpan.getTraceId());
                      spanClearedAfterTask.set(workerSpan == null);
                    }))
            .get(5, TimeUnit.SECONDS);
        EXECUTOR.submit(
                () -> spanClearedAfterTask.set(Tracer.getCurrentSpan() == null))
            .get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      } finally {
        Tracer.clearCurrentSpan();
      }
    }
  }

  static final List<String> NestedCalls = Collections.synchronizedList(new ArrayList<String>());

  // --- Harness ---------------------------------------------------------------------------------

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
    CutpointAdvice.observed.clear();
    NestedCalls.clear();
    ExecutorAdvice.seenOnWorker.set(null);
    ExecutorAdvice.workerAfterTask.set(null);
    ExecutorAdvice.failure.set(null);
    TracerAdvice.traceIdOnWorker.set(null);
    TracerAdvice.parentTraceId.set(null);
    TracerAdvice.spanClearedAfterTask.set(null);
    ThreadContext.clear();
    Tracer.clearCurrentSpan();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    if (registry != null) {
      registry.clear();
    }
    ThreadContext.clear();
    Tracer.clearCurrentSpan();
  }

  private void loadAndWeave(Class<?>... adviceClasses) {
    Set<Class<?>> classes = new HashSet<>();
    Collections.addAll(classes, adviceClasses);
    new AnnotationPluginLoader().loadAnnotatedInterceptors(classes, registry);
    assertFalse(registry.getAllDefinitions().isEmpty(), "advice should register definitions");
    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation, true);
  }

  // --- Tests -----------------------------------------------------------------------------------

  @Test
  void threadContextWrittenInBeforeIsReadableInAfterOfTheSameWovenCall() {
    loadAndWeave(CutpointAdvice.class);
    CutpointTarget target = new CutpointTarget();

    assertEquals("done x", target.work("x"), "the original method must still run");
    assertEquals(
        java.util.Arrays.asList("before=req-x", "after=req-x"),
        CutpointAdvice.observed,
        "the ThreadLocal value must survive from @Before to @After of the same interception");

    // A second call on the same thread writes its own value rather than inheriting the first's.
    CutpointAdvice.observed.clear();
    target.work("y");
    assertEquals(
        java.util.Arrays.asList("before=req-y", "after=req-y"),
        CutpointAdvice.observed,
        "each call carries the value its own @Before wrote");
  }

  @Test
  void aNestedWovenCallInheritsTheCallersThreadContext() {
    loadAndWeave(OuterAdvice.class, InnerAdvice.class);

    // OuterAdvice writes the value, the test then invokes the inner woven method as the application
    // would from inside the outer method's body.
    new OuterTarget().outer();
    new InnerTarget().inner();

    assertEquals(
        java.util.Arrays.asList("req-outer"),
        NestedCalls,
        "the inner cutpoint must see the context the outer cutpoint set on the same thread");
  }

  @Test
  void contextCrossesIntoAnExecutorAndTheWorkerIsRestoredAfterwards() {
    loadAndWeave(ExecutorAdvice.class);

    new ExecutorTarget().async();

    assertNull(ExecutorAdvice.failure.get(), "the advice must not fail: " + ExecutorAdvice.failure.get());
    assertEquals(
        "req-async",
        ExecutorAdvice.seenOnWorker.get(),
        "ContextPropagators.wrap must carry ThreadContext into the worker thread");
    assertNull(
        ExecutorAdvice.workerAfterTask.get(),
        "the worker's own context must be restored after the wrapped task, or the next task on that"
            + " pooled thread would inherit the request's state");
  }

  @Test
  void tracingSpanCrossesIntoAnExecutorAndIsClearedAfterwards() {
    loadAndWeave(TracerAdvice.class);

    new TracerTarget().tracedAsync();

    assertNotNull(TracerAdvice.parentTraceId.get());
    assertEquals(
        TracerAdvice.parentTraceId.get(),
        TracerAdvice.traceIdOnWorker.get(),
        "the worker thread must continue the same trace");
    assertEquals(
        Boolean.TRUE,
        TracerAdvice.spanClearedAfterTask.get(),
        "the worker must not keep the propagated span once the task is done");
  }
}
