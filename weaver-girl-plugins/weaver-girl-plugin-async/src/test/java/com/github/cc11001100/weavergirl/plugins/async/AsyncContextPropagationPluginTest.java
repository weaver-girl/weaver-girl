package com.github.cc11001100.weavergirl.plugins.async;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ContextCallable;
import com.github.cc11001100.weavergirl.api.context.ContextRunnable;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AsyncContextPropagationPlugin}.
 *
 * <p>These tests exercise the argument-wrapping logic directly (without a real ByteBuddy agent) by
 * invoking the interceptor's {@code before()} callback with a hand-built {@link MethodInvocation}.
 * The end-to-end context-propagation behavior is verified in {@link
 * AsyncContextPropagationIntegrationTest}.
 */
class AsyncContextPropagationPluginTest {

  private AsyncContextPropagationPlugin plugin;
  private StubRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new AsyncContextPropagationPlugin();
    registry = new StubRegistry();
    ThreadContext.clear();
  }

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
  }

  @Test
  void name_returnsAsyncContextPropagation() {
    assertEquals("async-context-propagation", plugin.name());
  }

  @Test
  void registerInterceptors_enabled_registersAllDefinitions() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);
    // execute x2, submit x3, invokeAll x2, invokeAny x2, schedule x4
    assertEquals(13, registry.definitions.size());
  }

  @Test
  void registerInterceptors_disabled_registersNothing() {
    Map<String, String> config = new HashMap<>();
    config.put("enabled", "false");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertTrue(registry.definitions.isEmpty());
  }

  @Test
  void registeredDefinitions_targetExecutorInterfaces() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);

    boolean hasExecutor = false;
    boolean hasExecutorService = false;
    boolean hasScheduledExecutorService = false;
    for (InterceptorDefinition def : registry.definitions) {
      String pattern = def.getPointcut().getClassMatcher().getPattern();
      if (pattern.equals("java.util.concurrent.Executor")) hasExecutor = true;
      if (pattern.equals("java.util.concurrent.ExecutorService")) hasExecutorService = true;
      if (pattern.equals("java.util.concurrent.ScheduledExecutorService"))
        hasScheduledExecutorService = true;
    }
    assertTrue(hasExecutor, "Should target java.util.concurrent.Executor");
    assertTrue(hasExecutorService, "Should target java.util.concurrent.ExecutorService");
    assertTrue(
        hasScheduledExecutorService, "Should target java.util.concurrent.ScheduledExecutorService");
  }

  @Test
  void registeredDefinitions_haveHighPriority() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);
    for (InterceptorDefinition def : registry.definitions) {
      assertTrue(
          def.getPriority() < 0,
          "Async propagation should run early (negative priority): " + def.getPriority());
    }
  }

  @Test
  void before_wrapsRunnableArgument() {
    Interceptor interceptor = getWrapInterceptor();
    Runnable original = () -> {};
    MethodInvocation inv =
        new MethodInvocation(Object.class, "execute", null, new Object[] {original});

    interceptor.before(inv);

    Object wrapped = inv.getArgument(0);
    assertInstanceOf(
        ContextRunnable.class, wrapped, "Runnable argument should be wrapped in ContextRunnable");
  }

  @Test
  void before_wrapsCallableArgument() {
    Interceptor interceptor = getWrapInterceptor();
    Callable<String> original = () -> "result";
    MethodInvocation inv =
        new MethodInvocation(Object.class, "submit", null, new Object[] {original});

    interceptor.before(inv);

    Object wrapped = inv.getArgument(0);
    assertInstanceOf(
        ContextCallable.class, wrapped, "Callable argument should be wrapped in ContextCallable");
  }

  @Test
  void before_wrapsSubmitRunnableWithResultFirstArgument() {
    Interceptor interceptor = getWrapInterceptor();
    Runnable original = () -> {};
    Object result = "result";
    MethodInvocation inv =
        new MethodInvocation(Object.class, "submit", null, new Object[] {original, result});

    interceptor.before(inv);

    assertInstanceOf(
        ContextRunnable.class,
        inv.getArgument(0),
        "First submit(Runnable, result) argument should be wrapped");
    assertSame(result, inv.getArgument(1), "Result argument should be left untouched");
  }

  @Test
  void before_wrapsCallableCollections() {
    Interceptor interceptor = getWrapInterceptor();
    Callable<String> first = () -> "one";
    Callable<String> second = () -> "two";
    List<Callable<String>> originals = Arrays.asList(first, second);
    MethodInvocation inv =
        new MethodInvocation(Object.class, "invokeAll", null, new Object[] {originals});

    interceptor.before(inv);

    Object wrapped = inv.getArgument(0);
    assertInstanceOf(List.class, wrapped);
    @SuppressWarnings("unchecked")
    List<Callable<String>> wrappedList = (List<Callable<String>>) wrapped;
    assertEquals(2, wrappedList.size());
    assertInstanceOf(ContextCallable.class, wrappedList.get(0));
    assertInstanceOf(ContextCallable.class, wrappedList.get(1));
    assertNotSame(
        originals,
        wrapped,
        "Collection wrappers should use a fresh list for immutable input collections");
  }

  @Test
  void before_wrapsEmptyCallableCollections() {
    Interceptor interceptor = getWrapInterceptor();
    MethodInvocation inv =
        new MethodInvocation(
            Object.class,
            "invokeAll",
            null,
            new Object[] {Collections.<Callable<String>>emptyList()});

    interceptor.before(inv);

    assertInstanceOf(List.class, inv.getArgument(0));
    assertTrue(((List<?>) inv.getArgument(0)).isEmpty());
  }

  @Test
  void before_leavesMixedCollectionsUntouched() {
    Interceptor interceptor = getWrapInterceptor();
    List<Object> mixed = Arrays.<Object>asList((Callable<String>) () -> "value", "not-callable");
    MethodInvocation inv =
        new MethodInvocation(Object.class, "invokeAll", null, new Object[] {mixed});

    interceptor.before(inv);

    assertSame(mixed, inv.getArgument(0));
  }

  @Test
  void before_doesNotDoubleWrapContextRunnable() {
    Interceptor interceptor = getWrapInterceptor();
    Runnable original = () -> {};
    ContextRunnable alreadyWrapped = new ContextRunnable(original);
    MethodInvocation inv =
        new MethodInvocation(Object.class, "execute", null, new Object[] {alreadyWrapped});

    interceptor.before(inv);

    Object arg = inv.getArgument(0);
    assertSame(alreadyWrapped, arg, "Already-wrapped ContextRunnable should not be double-wrapped");
  }

  @Test
  void before_doesNotDoubleWrapContextCallable() {
    Interceptor interceptor = getWrapInterceptor();
    Callable<String> original = () -> "result";
    ContextCallable<String> alreadyWrapped = new ContextCallable<>(original);
    MethodInvocation inv =
        new MethodInvocation(Object.class, "submit", null, new Object[] {alreadyWrapped});

    interceptor.before(inv);

    Object arg = inv.getArgument(0);
    assertSame(alreadyWrapped, arg, "Already-wrapped ContextCallable should not be double-wrapped");
  }

  @Test
  void before_leavesNonTaskArgumentsUntouched() {
    Interceptor interceptor = getWrapInterceptor();
    Object notATask = "just-a-string";
    MethodInvocation inv =
        new MethodInvocation(Object.class, "execute", null, new Object[] {notATask});

    interceptor.before(inv);

    assertSame(
        notATask, inv.getArgument(0), "Non-Runnable/Callable arguments should not be touched");
  }

  @Test
  void before_emptyArgumentsDoesNothing() {
    Interceptor interceptor = getWrapInterceptor();
    MethodInvocation inv = new MethodInvocation(Object.class, "execute", null, new Object[0]);

    // Should not throw
    assertDoesNotThrow(() -> interceptor.before(inv));
  }

  @Test
  void wrappedRunnable_propagatesThreadContextAcrossThreads() throws Exception {
    Interceptor interceptor = getWrapInterceptor();
    AtomicReference<String> seenTraceId = new AtomicReference<>();
    Runnable original = () -> seenTraceId.set(ThreadContext.get("traceId"));

    // Set context on the submitting thread
    ThreadContext.put("traceId", "abc-123");
    MethodInvocation inv =
        new MethodInvocation(Object.class, "execute", null, new Object[] {original});

    interceptor.before(inv);

    // Clear context on submitting thread — the worker should still see it
    ThreadContext.clear();
    Runnable wrapped = (Runnable) inv.getArgument(0);

    // Run on a different thread
    Thread worker = new Thread(wrapped);
    worker.start();
    worker.join();

    assertEquals(
        "abc-123",
        seenTraceId.get(),
        "Wrapped Runnable should propagate ThreadContext to the worker thread");
  }

  @Test
  void wrappedCallable_propagatesThreadContextAcrossThreads() throws Exception {
    Interceptor interceptor = getWrapInterceptor();
    Callable<String> original = () -> ThreadContext.get("traceId");

    ThreadContext.put("traceId", "xyz-789");
    MethodInvocation inv =
        new MethodInvocation(Object.class, "submit", null, new Object[] {original});

    interceptor.before(inv);

    ThreadContext.clear();
    @SuppressWarnings("unchecked")
    Callable<String> wrapped = (Callable<String>) inv.getArgument(0);

    // Run on a different thread
    final String[] result = new String[1];
    Thread worker =
        new Thread(
            () -> {
              try {
                result[0] = wrapped.call();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    worker.start();
    worker.join();

    assertEquals(
        "xyz-789",
        result[0],
        "Wrapped Callable should propagate ThreadContext to the worker thread");
  }

  private Interceptor getWrapInterceptor() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);
    // All definitions share the same interceptor instance
    return registry.definitions.get(0).getInterceptor();
  }

  // --- Stubs ---

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

  private static class StubRegistry implements InterceptorRegistry {
    final List<InterceptorDefinition> definitions = new ArrayList<>();

    @Override
    public void register(InterceptorDefinition definition) {
      definitions.add(definition);
    }

    @Override
    public boolean unregister(String name) {
      return false;
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
      return definitions;
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
      return definitions;
    }
  }
}
