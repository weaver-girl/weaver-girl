package com.github.cc11001100.weavergirl.core.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.annotation.DeclarePrecedence;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the property the dispatch cache exists for: with thousands of pointcuts registered, the
 * per-invocation lookup must stay O(1) — no registry rescan, no reflective class resolution, no
 * per-call allocation.
 *
 * <p>The registry used here counts how often the two expensive paths are entered. A regression that
 * reintroduces the per-call scan shows up as a growing counter (a structural failure), not as a
 * flaky wall-clock assertion.
 */
class DispatchScaleTest {

  private static final int POINTCUTS = 3000;

  @AfterEach
  void restore() {
    InterceptorHolder.setRegistry(null);
    InterceptorHolder.invalidateSnapshot();
    InterceptorEventPublisher.getInstance().clearHistory();
  }

  /** Counts the O(definitions) and reflective paths so the test can assert they are not hot. */
  private static final class CountingRegistry extends DefaultInterceptorRegistry {
    final AtomicInteger reflectiveLookups = new AtomicInteger();
    final AtomicInteger classLookups = new AtomicInteger();

    @Override
    public boolean matchesByReflection(ClassMatcher classMatcher, String className) {
      reflectiveLookups.incrementAndGet();
      return super.matchesByReflection(classMatcher, className);
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
      classLookups.incrementAndGet();
      return super.getInterceptorsForClass(className);
    }
  }

  private static InterceptorDefinition definition(
      String name, ClassMatcher classMatcher, MethodMatcher methodMatcher, int priority) {
    return new InterceptorDefinition(
        name, new Pointcut(classMatcher, methodMatcher), new Interceptor() {}, priority);
  }

  private static InterceptorDefinition exact(
      String name, String className, String methodName, int priority) {
    return definition(name, ClassMatcher.byName(className), MethodMatcher.byName(methodName), priority);
  }

  /**
   * 10,000 dispatches after warm-up: the registry is not scanned again and no class is resolved
   * reflectively again. This is the core scaling guarantee — the counters are independent of the
   * number of calls.
   */
  @Test
  void repeatedDispatchNeverRescansTheRegistry() {
    CountingRegistry registry = new CountingRegistry();
    for (int i = 0; i < POINTCUTS; i++) {
      registry.register(exact("p" + i, "scale.C" + i, "handle", 0));
    }
    // Non-exact class matchers cannot be indexed by name; each costs one reflective lookup when a
    // class plan is first built — and never again for that class.
    registry.register(
        definition(
            "iface-runnable",
            ClassMatcher.byInterface("java.lang.Runnable"),
            MethodMatcher.any(),
            0));
    registry.register(
        definition(
            "iface-callable",
            ClassMatcher.byInterface("java.util.concurrent.Callable"),
            MethodMatcher.any(),
            0));
    InterceptorHolder.setRegistry(registry);

    InterceptorHolder.DispatchView warm = InterceptorHolder.dispatch("scale.C0", "handle");
    assertEquals(1, warm.definitions.length, "only the definition registered for this class");
    assertEquals("p0", warm.definitions[0].getName());
    int reflectiveAfterWarmUp = registry.reflectiveLookups.get();
    assertTrue(
        reflectiveAfterWarmUp > 0,
        "the non-exact matchers are expected to resolve reflectively once");

    InterceptorHolder.DispatchView first = null;
    for (int i = 0; i < 10_000; i++) {
      InterceptorHolder.DispatchView view = InterceptorHolder.dispatch("scale.C0", "handle");
      if (first == null) {
        first = view;
      }
    }
    assertNotNull(first);
    assertSame(warm.definitions, first.definitions, "the view is cached, not rebuilt per call");
    assertEquals(
        reflectiveAfterWarmUp,
        registry.reflectiveLookups.get(),
        "no reflective class resolution on the dispatch hot path");
    assertEquals(0, registry.classLookups.get(), "the registry is never asked per call");
  }

  /**
   * Exact names and name patterns are filtered per method; ANY matchers stay candidates. A method
   * with no exact definition still dispatches the wildcard ones.
   */
  @Test
  void methodNameMatchersAreFilteredPerMethod() {
    CountingRegistry registry = new CountingRegistry();
    registry.register(exact("alpha", "scale.Target", "alpha", 0));
    registry.register(exact("beta", "scale.Target", "beta", 0));
    registry.register(
        definition("all", ClassMatcher.byName("scale.Target"), MethodMatcher.any(), 0));
    registry.register(
        definition(
            "pattern",
            ClassMatcher.byNamePattern("scale\\..*"),
            MethodMatcher.byNamePattern("gam.*"),
            0));
    InterceptorHolder.setRegistry(registry);

    assertEquals(
        java.util.Arrays.asList("alpha", "all"),
        names(InterceptorHolder.dispatch("scale.Target", "alpha")));
    assertEquals(
        java.util.Arrays.asList("beta", "all"),
        names(InterceptorHolder.dispatch("scale.Target", "beta")));
    // "gamma" has no exact-name definition but the ANY and the regex matcher still apply.
    assertEquals(
        java.util.Arrays.asList("all", "pattern"),
        names(InterceptorHolder.dispatch("scale.Target", "gamma")));
    // "delta" is not matched by the regex either.
    assertEquals(
        java.util.Arrays.asList("all"),
        names(InterceptorHolder.dispatch("scale.Target", "delta")));
  }

  /** A registration invalidates the cached plans: the next dispatch sees the new definition. */
  @Test
  void aRegistrationIsVisibleToTheNextDispatch() {
    CountingRegistry registry = new CountingRegistry();
    registry.register(exact("p0", "scale.C0", "handle", 0));
    InterceptorHolder.setRegistry(registry);

    assertEquals(1, InterceptorHolder.dispatch("scale.C0", "handle").definitions.length);

    registry.register(exact("late", "scale.C0", "handle", 0));
    assertEquals(
        2,
        InterceptorHolder.dispatch("scale.C0", "handle").definitions.length,
        "the generation key must invalidate the cached view");
  }

  /** Dispatch order must match the registry's: priority first, then @DeclarePrecedence. */
  @Test
  void dispatchHonoursDeclarePrecedence() {
    CountingRegistry registry = new CountingRegistry();
    registry.register(exact("SecondOrderedAspect", "scale.Svc", "run", 0));
    registry.register(exact("FirstOrderedAspect", "scale.Svc", "run", 0));
    registry.register(exact("holder", "scale.Unrelated", "run", 0));
    // The definition whose aspect class carries the declaration.
    InterceptorDefinition holder =
        new InterceptorDefinition(
            "annotation-" + PrecedenceHolder.class.getName() + "-x",
            new Pointcut(
                ClassMatcher.byName("scale.Unrelated"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(holder);
    InterceptorHolder.setRegistry(registry);

    assertEquals(
        java.util.Arrays.asList("FirstOrderedAspect", "SecondOrderedAspect"),
        names(InterceptorHolder.dispatch("scale.Svc", "run")));
  }

  @DeclarePrecedence("FirstOrderedAspect, SecondOrderedAspect")
  static class PrecedenceHolder {}

  /**
   * A bulk load of thousands of pointcuts completes without the per-registration precedence
   * rebuild, and every one of them is still dispatched correctly afterwards.
   */
  @Test
  void thousandsOfPointcutsRegisterAndDispatchCorrectly() {
    CountingRegistry registry = new CountingRegistry();
    long start = System.nanoTime();
    for (int i = 0; i < 5000; i++) {
      registry.register(exact("bulk" + i, "bulk.C" + i, "work", 0));
    }
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

    InterceptorHolder.setRegistry(registry);
    assertEquals(0, registry.reflectiveLookups.get(), "registration must not resolve classes");
    for (int i = 0; i < 5000; i += 499) {
      InterceptorHolder.DispatchView view = InterceptorHolder.dispatch("bulk.C" + i, "work");
      assertEquals(1, view.definitions.length);
      assertEquals("bulk" + i, view.definitions[0].getName());
      assertEquals("bulk.C" + i + ".work", view.methodKey);
    }
    // Generous bound: catches a return to quadratic registration (minutes for 5000) without
    // failing on a loaded CI machine.
    assertTrue(elapsedMillis < 10_000, "bulk registration took " + elapsedMillis + "ms");
  }

  private static List<String> names(InterceptorHolder.DispatchView view) {
    java.util.List<String> names = new java.util.ArrayList<>(view.definitions.length);
    for (InterceptorDefinition def : view.definitions) {
      names.add(def.getName());
    }
    return names;
  }
}
