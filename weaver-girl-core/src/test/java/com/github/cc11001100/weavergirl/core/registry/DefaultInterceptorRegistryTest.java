// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistryTest.java
package com.github.cc11001100.weavergirl.core.registry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.cc11001100.weavergirl.annotation.DeclarePrecedence;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.security.SecurityAuditLog;
import com.github.cc11001100.weavergirl.api.security.SecurityPolicy;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultInterceptorRegistryTest {

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
  }

  @AfterEach
  void tearDown() {
    // register() under a deny policy records to the global audit log and event history;
    // clear both so test classes sharing the same fork see a clean slate.
    SecurityAuditLog.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
  }

  @Test
  void register_andLookupByClassName_returnsMatchingDefinition() {
    InterceptorDefinition def = createDefinition("test", "com.example.TargetService", "doWork");
    registry.register(def);

    List<InterceptorDefinition> result =
        registry.getInterceptorsForClass("com.example.TargetService");
    assertEquals(1, result.size());
    assertEquals("test", result.get(0).getName());
  }

  @Test
  void register_nullDefinition_ignored() {
    registry.register(null);
    assertEquals(0, registry.getAllDefinitions().size());
  }

  @Test
  void getInterceptorsForClass_noMatch_returnsEmptyList() {
    InterceptorDefinition def = createDefinition("test", "com.example.TargetService", "doWork");
    registry.register(def);

    List<InterceptorDefinition> result =
        registry.getInterceptorsForClass("com.example.OtherService");
    assertTrue(result.isEmpty());
  }

  @Test
  void register_multipleDefinitions_sortedByPriority() {
    InterceptorDefinition low = createDefinition("low", "com.example.Svc", "run", 10);
    InterceptorDefinition high = createDefinition("high", "com.example.Svc", "run", 1);
    registry.register(low);
    registry.register(high);

    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals(2, result.size());
    assertEquals("high", result.get(0).getName());
    assertEquals("low", result.get(1).getName());
  }

  @Test
  void clear_removesAllDefinitions() {
    registry.register(createDefinition("test", "com.example.Svc", "run"));
    registry.clear();
    assertEquals(0, registry.getAllDefinitions().size());
  }

  // --- generation counter ---

  @Test
  void getGeneration_startsAtZero() {
    assertEquals(0, registry.getGeneration());
  }

  @Test
  void getGeneration_incrementsOnRegisterUnregisterAndClear() {
    long base = registry.getGeneration();
    registry.register(createDefinition("g1", "com.example.Svc", "run"));
    assertEquals(base + 1, registry.getGeneration());

    assertTrue(registry.unregister("g1"));
    assertEquals(base + 2, registry.getGeneration());

    registry.register(createDefinition("g2", "com.example.Svc", "run"));
    registry.clear();
    assertEquals(base + 4, registry.getGeneration());
  }

  @Test
  void getGeneration_duplicateNameRegisterStillBumps() {
    registry.register(createDefinition("dup", "com.example.A", "run"));
    long afterFirst = registry.getGeneration();
    registry.register(createDefinition("dup", "com.example.B", "run"));
    assertEquals(afterFirst + 1, registry.getGeneration());
    // Only the replacement remains
    assertEquals(1, registry.getAllDefinitions().size());
  }

  // --- reload hooks ---

  @Test
  void reloadHook_firesOnRegisterUnregisterAndClear() {
    AtomicInteger calls = new AtomicInteger();
    Runnable hook = calls::incrementAndGet;
    registry.addReloadHook(hook);

    registry.register(createDefinition("h1", "com.example.Svc", "run"));
    assertEquals(1, calls.get());

    registry.unregister("h1");
    assertEquals(2, calls.get());

    registry.register(createDefinition("h2", "com.example.Svc", "run"));
    registry.clear();
    assertEquals(4, calls.get());
  }

  @Test
  void addReloadHook_nullHook_ignored() {
    registry.addReloadHook(null);
    // Must not throw; registration still works and bumps generation.
    assertDoesNotThrow(() -> registry.register(createDefinition("n", "com.example.Svc", "run")));
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void removeReloadHook_stopsInvocations() {
    AtomicInteger calls = new AtomicInteger();
    Runnable hook = calls::incrementAndGet;
    registry.addReloadHook(hook);
    registry.removeReloadHook(hook);

    registry.register(createDefinition("r", "com.example.Svc", "run"));
    assertEquals(0, calls.get());
  }

  @Test
  void removeReloadHook_unknownHook_doesNotThrow() {
    assertDoesNotThrow(() -> registry.removeReloadHook(() -> {}));
  }

  @Test
  void reloadHook_throwingHook_doesNotBreakRegistration() {
    AtomicBoolean goodHookRan = new AtomicBoolean(false);
    registry.addReloadHook(
        () -> {
          throw new IllegalStateException("hook boom");
        });
    registry.addReloadHook(() -> goodHookRan.set(true));

    // A throwing hook must be swallowed (log.warn) and must not prevent
    // the mutation or subsequent hooks from running.
    assertDoesNotThrow(() -> registry.register(createDefinition("t", "com.example.Svc", "run")));
    assertTrue(goodHookRan.get());
    assertEquals(1, registry.getAllDefinitions().size());
  }

  // --- security policy ---

  @Test
  void register_deniedBySecurityPolicy_isIgnoredAndAudited() {
    registry.setSecurityPolicy(SecurityPolicy.builder().denyPattern("*").build());

    registry.register(createDefinition("blocked", "com.example.Svc", "run"));

    assertTrue(registry.getAllDefinitions().isEmpty());
    assertTrue(
        SecurityAuditLog.getRecords().stream()
            .anyMatch(r -> "INTERCEPT".equals(r.getOperation()) && "DENIED".equals(r.getResult())));
  }

  @Test
  void setSecurityPolicy_nullPolicy_fallsBackToDefaultAllow() {
    registry.setSecurityPolicy(SecurityPolicy.builder().denyPattern("*").build());
    registry.setSecurityPolicy(null);

    registry.register(createDefinition("allowed", "com.example.Svc", "run"));
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void register_allowPatternMismatch_isIgnored() {
    registry.setSecurityPolicy(
        SecurityPolicy.builder().allowPattern("com.allowed.*").defaultAllow(false).build());

    registry.register(createDefinition("nope", "com.example.Svc", "run"));
    assertTrue(registry.getAllDefinitions().isEmpty());

    registry.register(createDefinition("yep", "com.allowed.Svc", "run"));
    assertEquals(1, registry.getAllDefinitions().size());
  }

  // --- input validation ---

  @Test
  void getInterceptorsForClass_nullOrBlank_throws() {
    assertThrows(IllegalArgumentException.class, () -> registry.getInterceptorsForClass(null));
    assertThrows(IllegalArgumentException.class, () -> registry.getInterceptorsForClass(""));
    assertThrows(IllegalArgumentException.class, () -> registry.getInterceptorsForClass("   "));
  }

  @Test
  void unregister_nullOrBlank_throws() {
    assertThrows(IllegalArgumentException.class, () -> registry.unregister(null));
    assertThrows(IllegalArgumentException.class, () -> registry.unregister(""));
    assertFalse(registry.unregister("does-not-exist"));
  }

  @Test
  void getAllDefinitions_isUnmodifiable() {
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            registry
                .getAllDefinitions()
                .add(createDefinition("x", "com.example.Svc", "run")));
  }

  // --- index rebuild / stale-index visibility ---

  @Test
  void indexRebuilt_afterUnregisterAndClear() {
    registry.register(createDefinition("a", "com.example.Svc", "run"));
    assertEquals(1, registry.getInterceptorsForClass("com.example.Svc").size());

    assertTrue(registry.unregister("a"));
    assertTrue(registry.getInterceptorsForClass("com.example.Svc").isEmpty());

    registry.register(createDefinition("b", "com.example.Svc", "run"));
    assertEquals(1, registry.getInterceptorsForClass("com.example.Svc").size());
    registry.clear();
    assertTrue(registry.getInterceptorsForClass("com.example.Svc").isEmpty());
  }

  @Test
  void rebuildIndex_sortsEachBucketByPriority() {
    // Both definitions share the same class pattern, so they land in one index
    // bucket; the bucket sort must order by priority.
    registry.register(createDefinition("low", "com.example.Svc", "run", 10));
    registry.register(createDefinition("high", "com.example.Svc", "run", 1));

    // First lookup triggers rebuildIndex and exercises the bucket sort.
    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals("high", result.get(0).getName());
    assertEquals("low", result.get(1).getName());
  }

  // --- @DeclarePrecedence ordering ---

  @DeclarePrecedence("FirstOrderedAspect, SecondOrderedAspect")
  static class PrecedenceHolder {}

  static class FirstOrderedAspect {}

  static class SecondOrderedAspect {}

  /** Definition name whose {@code getAspectClassName()} resolves to the loadable holder class. */
  private static String holderDefinitionName() {
    return "annotation-" + PrecedenceHolder.class.getName() + "-x";
  }

  @Test
  void precedenceMap_appliesDeclarePrecedenceOrdering() {
    // The aspect class name used for lookup is the definition name, so name the
    // definitions exactly like the simple names in the @DeclarePrecedence value.
    InterceptorDefinition second =
        new InterceptorDefinition(
            "SecondOrderedAspect",
            new Pointcut(
                ClassMatcher.byName("com.example.Svc"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    InterceptorDefinition first =
        new InterceptorDefinition(
            "FirstOrderedAspect",
            new Pointcut(
                ClassMatcher.byName("com.example.Svc"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    // A definition whose aspect class carries the @DeclarePrecedence declaration.
    InterceptorDefinition holder =
        new InterceptorDefinition(
            holderDefinitionName(),
            new Pointcut(
                ClassMatcher.byName("com.example.Unrelated"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(second);
    registry.register(first);
    registry.register(holder);

    // Both definitions carry equal priority (0); precedence must order
    // FirstOrderedAspect ahead of SecondOrderedAspect despite registration order.
    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals(2, result.size());
    assertEquals("FirstOrderedAspect", result.get(0).getName());
    assertEquals("SecondOrderedAspect", result.get(1).getName());
  }

  @Test
  void precedenceMap_priorityStillAppliesWhenNoAspectInMap() {
    InterceptorDefinition low = createDefinition("low", "com.example.Svc", "run", 10);
    InterceptorDefinition high = createDefinition("high", "com.example.Svc", "run", 1);
    registry.register(low);
    registry.register(high);

    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals("high", result.get(0).getName());
  }

  @Test
  void refreshPrecedence_unknownAspectClass_doesNotThrow() {
    // getAspectClassName() returns the definition name, which is not loadable —
    // refreshPrecedence must swallow the ClassNotFoundException and continue.
    registry.register(createDefinition("not.a.RealAspect-xyz", "com.example.Svc", "run"));
    assertEquals(1, registry.getInterceptorsForClass("com.example.Svc").size());
  }

  @Test
  void refreshPrecedence_aspectWithoutAnnotation_doesNotThrow() {
    // String is loadable but carries no @DeclarePrecedence.
    InterceptorDefinition def =
        new InterceptorDefinition(
            "java.lang.String",
            new Pointcut(ClassMatcher.byName("com.example.Svc"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(def);
    assertEquals(1, registry.getInterceptorsForClass("com.example.Svc").size());
  }

  @Test
  void unregister_clearsStalePrecedenceEntry() {
    InterceptorDefinition holder =
        new InterceptorDefinition(
            holderDefinitionName(),
            new Pointcut(
                ClassMatcher.byName("com.example.Unrelated"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(holder);
    assertTrue(registry.unregister(holderDefinitionName()));

    // After removal, precedenceMap must be empty again — a later equal-priority
    // lookup must fall back to registration order rather than stale precedence.
    InterceptorDefinition first = createDefinition("FirstOrderedAspect", "com.example.Svc", "run");
    InterceptorDefinition second =
        createDefinition("SecondOrderedAspect", "com.example.Svc", "run");
    registry.register(first);
    registry.register(second);
    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals("FirstOrderedAspect", result.get(0).getName());
  }

  // --- matchesByReflection ---

  @Test
  void matchesByReflection_unknownCandidateClass_returnsFalse() {
    ClassMatcher matcher = ClassMatcher.byInterface("java.io.Serializable");
    assertFalse(registry.matchesByReflection(matcher, "com.example.DoesNotExist"));
  }

  @Test
  void matchesByReflection_interfaceMatch() {
    ClassMatcher matcher = ClassMatcher.byInterface("java.io.Serializable");
    assertTrue(registry.matchesByReflection(matcher, "java.lang.String"));
    assertFalse(registry.matchesByReflection(matcher, "java.lang.Object"));
    // Pattern that is not an interface must not match.
    assertFalse(
        registry.matchesByReflection(
            ClassMatcher.byInterface("java.lang.String"), "java.lang.String"));
    assertFalse(
        registry.matchesByReflection(
            ClassMatcher.byInterface("com.example.DoesNotExist"), "java.lang.String"));
  }

  @Test
  void matchesByReflection_superClassMatch() {
    ClassMatcher matcher = ClassMatcher.bySuperClass("java.lang.Exception");
    assertTrue(registry.matchesByReflection(matcher, "java.lang.RuntimeException"));
    // The superclass itself is excluded.
    assertFalse(registry.matchesByReflection(matcher, "java.lang.Exception"));
    assertFalse(registry.matchesByReflection(matcher, "java.lang.String"));
    assertFalse(
        registry.matchesByReflection(
            ClassMatcher.bySuperClass("com.example.DoesNotExist"), "java.lang.String"));
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface TestMarker {}

  @TestMarker
  static class MarkedTarget {}

  static class UnmarkedTarget {}

  @Test
  void matchesByReflection_annotationMatch() {
    String annoName = TestMarker.class.getName();
    ClassMatcher matcher = ClassMatcher.byAnnotation(annoName);
    assertTrue(registry.matchesByReflection(matcher, MarkedTarget.class.getName()));
    assertFalse(registry.matchesByReflection(matcher, UnmarkedTarget.class.getName()));
    // Non-annotation pattern and unloadable annotation both return false.
    assertFalse(
        registry.matchesByReflection(
            ClassMatcher.byAnnotation("java.lang.String"), MarkedTarget.class.getName()));
    assertFalse(
        registry.matchesByReflection(
            ClassMatcher.byAnnotation("com.example.DoesNotExist"),
            MarkedTarget.class.getName()));
  }

  @Test
  void matchesByReflection_namePatternType_returnsFalse() {
    ClassMatcher matcher = ClassMatcher.byNamePattern("com\\.example\\..*");
    assertFalse(registry.matchesByReflection(matcher, "java.lang.String"));
  }

  @Test
  void getInterceptorsForClass_interfaceMatcher_selectsImplementorsViaReflection() {
    InterceptorDefinition def =
        new InterceptorDefinition(
            "iface",
            new Pointcut(
                ClassMatcher.byInterface("java.io.Serializable"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(def);

    assertEquals(1, registry.getInterceptorsForClass("java.lang.String").size());
    assertTrue(registry.getInterceptorsForClass("java.lang.Object").isEmpty());
  }

  @Test
  void getInterceptorsForClass_nonReflectiveNonMatchingType_excluded() {
    InterceptorDefinition def =
        new InterceptorDefinition(
            "iface",
            new Pointcut(
                ClassMatcher.byInterface("java.io.Serializable"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(def);

    // Unloadable class: matches(String) is false and reflection bails out.
    assertTrue(registry.getInterceptorsForClass("com.example.DoesNotExist").isEmpty());
  }

  // --- lifecycle event publish failure is swallowed ---

  @Test
  void register_throwingEventListener_doesNotBreakRegistration() {
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    // NOTE: the publisher swallows Exception from listeners internally, so an
    // Error is needed to reach the registry's catch-all around publish().
    com.github.cc11001100.weavergirl.api.event.InterceptorEventListener boom =
        event -> {
          throw new AssertionError("listener boom");
        };
    publisher.addListener(boom);
    try {
      assertDoesNotThrow(
          () -> registry.register(createDefinition("ev", "com.example.Svc", "run")));
      assertEquals(1, registry.getAllDefinitions().size());
    } finally {
      publisher.removeListener(boom);
    }
  }

  @Test
  void unregister_throwingEventListener_stillRemoves() {
    registry.register(createDefinition("ev", "com.example.Svc", "run"));
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    com.github.cc11001100.weavergirl.api.event.InterceptorEventListener boom =
        event -> {
          throw new AssertionError("listener boom");
        };
    publisher.addListener(boom);
    try {
      assertTrue(registry.unregister("ev"));
      assertTrue(registry.getAllDefinitions().isEmpty());
    } finally {
      publisher.removeListener(boom);
    }
  }

  @Test
  void clear_throwingEventListener_stillClears() {
    registry.register(createDefinition("ev", "com.example.Svc", "run"));
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    com.github.cc11001100.weavergirl.api.event.InterceptorEventListener boom =
        event -> {
          throw new AssertionError("listener boom");
        };
    publisher.addListener(boom);
    try {
      assertDoesNotThrow(() -> registry.clear());
      assertTrue(registry.getAllDefinitions().isEmpty());
    } finally {
      publisher.removeListener(boom);
    }
  }

  @Test
  void clear_throwingDestroy_isSwallowed() {
    Interceptor failing =
        new Interceptor() {
          @Override
          public void destroy() {
            throw new IllegalStateException("destroy boom");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "failing-clear",
            new Pointcut(ClassMatcher.byName("com.example.Svc"), MethodMatcher.byName("run")),
            failing));
    assertDoesNotThrow(() -> registry.clear());
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  // --- InterceptorDefinition.getAspectClassName edge (annotation- without second dash) ---

  @Test
  void definitionName_annotationPrefixWithoutDash_fallsBackToName() {
    InterceptorDefinition def = createDefinition("annotation-lonely", "com.example.Svc", "run");
    assertEquals("annotation-lonely", def.getAspectClassName());
    registry.register(def);
    assertEquals(1, registry.getInterceptorsForClass("com.example.Svc").size());
  }

  // --- precedence tie-break: equal precedence + equal priority falls back to priority compare ---

  @Test
  void precedenceMap_equalPrecedenceAndPriority_comparatorTailCovered() {
    // A mocked definition lets two distinct instances share the same precedence
    // key and priority while remaining distinct list entries (Mockito mocks
    // use identity equality, unlike real InterceptorDefinition#equals).
    InterceptorDefinition holder =
        new InterceptorDefinition(
            holderDefinitionName(),
            new Pointcut(
                ClassMatcher.byName("com.example.Unrelated"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(holder);

    InterceptorDefinition first = mock(InterceptorDefinition.class);
    InterceptorDefinition second = mock(InterceptorDefinition.class);
    Pointcut pointcut =
        new Pointcut(ClassMatcher.byName("com.example.Svc"), MethodMatcher.byName("run"));
    for (InterceptorDefinition mockDef : new InterceptorDefinition[] {first, second}) {
      when(mockDef.getName()).thenReturn("mock-" + System.identityHashCode(mockDef));
      when(mockDef.getPointcut()).thenReturn(pointcut);
      // Neither mock is in the precedence map (null key) and both share
      // priority 5, so the comparator reaches its final priority tie-break.
      when(mockDef.getAspectClassName()).thenReturn("NotInPrecedenceMap");
      when(mockDef.getPriority()).thenReturn(5);
    }
    registry.register(first);
    registry.register(second);

    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals(2, result.size());
  }

  // --- refreshPrecedence null/empty aspect names are skipped ---

  @Test
  void refreshPrecedence_emptyAspectName_skipped() {
    // "annotation--x" parses to an empty aspect name via getAspectClassName().
    registry.register(createDefinition("annotation--x", "com.example.Svc", "run"));
    assertEquals(1, registry.getInterceptorsForClass("com.example.Svc").size());
  }

  @Test
  void refreshPrecedence_nullAspectName_skipped() {
    // A mocked definition can return a null aspect class name, which the
    // null-guard in refreshPrecedence() must skip without throwing.
    InterceptorDefinition mockDef = mock(InterceptorDefinition.class);
    Pointcut pointcut =
        new Pointcut(ClassMatcher.byName("com.example.Svc"), MethodMatcher.byName("run"));
    when(mockDef.getName()).thenReturn("mock-null-aspect");
    when(mockDef.getPointcut()).thenReturn(pointcut);
    when(mockDef.getInterceptor()).thenReturn(new Interceptor() {});
    // getAspectClassName() is deliberately unstubbed: Mockito's default answer
    // returns null, exercising the `aspectName == null` branch.

    assertDoesNotThrow(() -> registry.register(mockDef));
    assertEquals(1, registry.getInterceptorsForClass("com.example.Svc").size());
  }

  // --- contains-guards: indexed bucket already holds the definition ---

  @Test
  void lookup_annotationMatcherMatchingItsOwnPattern_noDuplicate() {
    // The class name equals the annotation pattern, so the definition is found
    // both via the class index and via matcher.matches() — the contains-guard
    // must prevent it from appearing twice.
    registry.register(
        new InterceptorDefinition(
            "anno",
            new Pointcut(
                ClassMatcher.byAnnotation("com.example.Foo"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0));

    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Foo");
    assertEquals(1, result.size());
  }

  @Test
  void lookup_interfaceMatcherMatchingItsOwnPattern_noDuplicate() {
    // Querying the interface name itself: the index bucket already contains the
    // definition, and matchesByReflection() also matches (an interface is
    // assignable from itself) — the contains-guard must prevent duplication.
    registry.register(
        new InterceptorDefinition(
            "iface",
            new Pointcut(
                ClassMatcher.byInterface("java.io.Serializable"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0));

    List<InterceptorDefinition> result = registry.getInterceptorsForClass("java.io.Serializable");
    assertEquals(1, result.size());
  }

  // --- @DeclarePrecedence with blank/duplicate entries skips them ---

  @DeclarePrecedence("FirstOrderedAspect, , FirstOrderedAspect")
  static class SloppyPrecedenceHolder {}

  @Test
  void refreshPrecedence_blankAndDuplicateEntries_skipped() {
    InterceptorDefinition holder =
        new InterceptorDefinition(
            "annotation-" + SloppyPrecedenceHolder.class.getName() + "-x",
            new Pointcut(
                ClassMatcher.byName("com.example.Unrelated"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(holder);

    InterceptorDefinition first = createDefinition("FirstOrderedAspect", "com.example.Svc", "run");
    InterceptorDefinition other = createDefinition("OtherAspect", "com.example.Svc", "run");
    registry.register(other);
    registry.register(first);

    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals(2, result.size());
    assertEquals("FirstOrderedAspect", result.get(0).getName());
  }

  // --- precedence ordering inside rebuildIndex buckets ---

  @Test
  void rebuildIndex_appliesPrecedenceOrderingWithinBucket() {
    InterceptorDefinition holder =
        new InterceptorDefinition(
            holderDefinitionName(),
            new Pointcut(
                ClassMatcher.byName("com.example.Unrelated"), MethodMatcher.byName("run")),
            new Interceptor() {},
            0);
    registry.register(holder);
    // Register in reverse precedence order with equal priority.
    registry.register(createDefinition("SecondOrderedAspect", "com.example.Svc", "run"));
    registry.register(createDefinition("FirstOrderedAspect", "com.example.Svc", "run"));

    // First lookup triggers rebuildIndex; its bucket sort must apply precedence
    // before the query-time sort sees an already-ordered list.
    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals("FirstOrderedAspect", result.get(0).getName());
    assertEquals("SecondOrderedAspect", result.get(1).getName());
  }

  private InterceptorDefinition createDefinition(String name, String className, String methodName) {
    return createDefinition(name, className, methodName, 0);
  }

  private InterceptorDefinition createDefinition(
      String name, String className, String methodName, int priority) {
    Pointcut pointcut =
        new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName(methodName));
    Interceptor interceptor = new Interceptor() {};
    return new InterceptorDefinition(name, pointcut, interceptor, priority);
  }
}
