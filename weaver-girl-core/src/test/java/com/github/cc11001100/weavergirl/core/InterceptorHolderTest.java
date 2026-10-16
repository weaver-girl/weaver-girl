package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The static bridge the inlined advice calls back into. */
class InterceptorHolderTest {

  @AfterEach
  void restore() {
    InterceptorHolder.setRegistry(null);
    InterceptorHolder.setRetransformHook(null);
  }

  private static InterceptorDefinition definition(String name, ClassMatcher matcher, int priority) {
    return new InterceptorDefinition(
        name,
        new Pointcut(matcher, MethodMatcher.any()),
        new com.github.cc11001100.weavergirl.api.interceptor.Interceptor() {},
        priority);
  }

  /** With no registry installed the lookup answers nothing instead of failing. */
  @Test
  void aMissingRegistryAnswersNothing() {
    InterceptorHolder.setRegistry(null);
    assertNull(InterceptorHolder.getRegistry());
    assertTrue(InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String").isEmpty());
  }

  /**
   * A registry that is not the built-in one is asked directly, and the answer is whatever it gives
   * back.
   */
  @Test
  void aForeignRegistryIsAskedDirectly() {
    InterceptorDefinition def =
        definition("foreign", ClassMatcher.byName("java.lang.String"), 0);
    InterceptorHolder.setRegistry(
        new InterceptorRegistry() {
          @Override
          public void register(InterceptorDefinition definition) {}

          @Override
          public boolean unregister(String name) {
            return false;
          }

          @Override
          public List<InterceptorDefinition> getInterceptorsForClass(String className) {
            return "java.lang.String".equals(className)
                ? Collections.singletonList(def)
                : Collections.emptyList();
          }

          @Override
          public List<InterceptorDefinition> getAllDefinitions() {
            return Collections.singletonList(def);
          }
        });

    assertEquals(1, InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String").size());
    assertTrue(InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.Integer").isEmpty());
  }

  /** Exact-name entries come from the snapshot, and a second read reuses it unchanged. */
  @Test
  void exactNameEntriesAreServedFromTheSnapshot() {
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    registry.register(definition("low", ClassMatcher.byName("java.lang.String"), 10));
    registry.register(definition("high", ClassMatcher.byName("java.lang.String"), 1));
    InterceptorHolder.setRegistry(registry);

    List<InterceptorDefinition> first =
        InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String");
    assertEquals(2, first.size());
    assertEquals("high", first.get(0).getName(), "lower priority numbers come first");

    // Nothing changed, so the cached snapshot is reused rather than rebuilt.
    assertEquals(2, InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String").size());
    assertTrue(InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.Integer").isEmpty());
  }

  /** A pattern and an interface matcher are scanned, and only the classes they fit are returned. */
  @Test
  void nonExactMatchersAreScanned() {
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    registry.register(definition("pattern", ClassMatcher.byNamePattern("java\\.lang\\.S.*"), 0));
    registry.register(definition("iface", ClassMatcher.byInterface("java.lang.Runnable"), 0));
    registry.register(definition("parent", ClassMatcher.bySuperClass("java.lang.Number"), 0));
    InterceptorHolder.setRegistry(registry);

    List<InterceptorDefinition> strings =
        InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String");
    assertEquals(1, strings.size());
    assertEquals("pattern", strings.get(0).getName());

    List<InterceptorDefinition> threads =
        InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.Thread");
    assertTrue(threads.stream().anyMatch(def -> def.getName().equals("iface")));

    List<InterceptorDefinition> integers =
        InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.Integer");
    assertTrue(integers.stream().anyMatch(def -> def.getName().equals("parent")));
  }

  /** Registering something new invalidates the snapshot, so the next read sees it. */
  @Test
  void aChangedRegistryRebuildsTheSnapshot() {
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    assertTrue(InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String").isEmpty());

    registry.register(definition("added", ClassMatcher.byName("java.lang.String"), 0));
    assertEquals(1, InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String").size());

    InterceptorHolder.invalidateSnapshot();
    assertEquals(1, InterceptorHolder.getInterceptorsForClassSnapshot("java.lang.String").size());
  }

  /** The retransform callback is installed, read back, and cleared. */
  @Test
  void theRetransformHookRoundTrips() {
    assertNull(InterceptorHolder.getRetransformHook());
    Runnable hook = () -> {};
    InterceptorHolder.setRetransformHook(hook);
    assertSame(hook, InterceptorHolder.getRetransformHook());
    InterceptorHolder.setRetransformHook(null);
    assertNull(InterceptorHolder.getRetransformHook());
  }

  /** Counters, the monitor and the circuit breaker all accept a call through the bridge. */
  @Test
  void theDelegatesAcceptACall() {
    InterceptorHolder.incrementInterceptorInvocationCount();
    InterceptorHolder.incrementInterceptorErrorCount();
    InterceptorHolder.recordInterceptTime(1_000L);
    assertTrue(InterceptorHolder.shouldInvoke("holder-probe"));
    InterceptorHolder.recordInterceptorSuccess("holder-probe");
    InterceptorHolder.recordInterceptorFailure("holder-probe");
    InterceptorHolder.recordOutcome("holder-probe", true, 1_000L);
    assertNotNull(InterceptorHolder.getBreakerSnapshots());
    assertDoesNotThrow(() -> InterceptorHolder.logInterceptorError("holder-probe", "before",
        new IllegalStateException("boom")));
  }

  /** A caller that is a real, loadable class is recorded in full. */
  @Test
  void aReachableCallerIsRecorded() {
    MethodInvocation invocation = new MethodInvocation(String.class, "length", null, null);
    InterceptorHolder.captureCaller(invocation);
    assertNotNull(invocation.getCallerClass(), "no caller was found");
    assertNotNull(invocation.getCallerMethodName());
  }

  /** A target with no class still finds a caller, skipping the target-class comparison. */
  @Test
  void aTargetWithoutAClassStillFindsACaller() {
    MethodInvocation invocation = new MethodInvocation(null, "run", null, null);
    InterceptorHolder.captureCaller(invocation);
    assertNotNull(invocation.getCallerMethodName());
  }

  /**
   * A caller whose class cannot be loaded still contributes its method and line, with a null class.
   */
  @Test
  void anUnloadableCallerKeepsItsMethod() throws Exception {
    ClassLoader hiding =
        new ClassLoader(InterceptorHolderTest.class.getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // Everything except the JDK is hidden, so whichever frame wins cannot be loaded.
            if (!name.startsWith("java.")) {
              throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
          }
        };
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(hiding);
    try {
      MethodInvocation invocation = new MethodInvocation(String.class, "length", null, null);
      InterceptorHolder.captureCaller(invocation);
      assertNull(invocation.getCallerClass(), "an unloadable caller class was resolved");
      assertNotNull(invocation.getCallerMethodName());
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  /**
   * Frames that belong to the intercepted class, the agent, the test runner or the JDK sit between
   * the intercepted method and the real caller, and each kind is skipped until a usable frame is
   * reached.
   */
  @Test
  void infrastructureFramesAreSkipped() {
    // The target is this test class, and the call passes through two of its own frames plus a stream
    // frame, so the scan meets the target class, the JDK and the test runner before the real caller.
    MethodInvocation invocation =
        new MethodInvocation(InterceptorHolderTest.class, "relay", null, null);
    relay(invocation);
    assertNotNull(invocation.getCallerMethodName(), "every frame was skipped");

    // A call made from inside the agent package is skipped as infrastructure.
    MethodInvocation fromAgent = new MethodInvocation(String.class, "length", null, null);
    InterceptorHolder.captureCaller(fromAgent);
    assertNotNull(fromAgent.getCallerMethodName());
  }

  /** Adds one more frame of this class above the capture, so the scan sees the target class. */
  private void relay(MethodInvocation invocation) {
    java.util.stream.Stream.of(invocation).forEach(this::handoff);
  }

  private void handoff(MethodInvocation invocation) {
    InterceptorHolder.captureCaller(invocation);
  }
}
