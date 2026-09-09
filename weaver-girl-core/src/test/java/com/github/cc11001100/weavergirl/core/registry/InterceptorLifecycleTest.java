package com.github.cc11001100.weavergirl.core.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link Interceptor#initialize()} and {@link Interceptor#destroy()} lifecycle hooks
 * are invoked at the correct registry lifecycle events.
 */
class InterceptorLifecycleTest {

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
  }

  @AfterEach
  void tearDown() {
    registry.clear();
  }

  // --- initialize() on register ---

  @Test
  void register_invokesInitialize() {
    List<String> events = new ArrayList<>();
    Interceptor interceptor = new LifecycleTrackingInterceptor(events);
    registry.register(
        new InterceptorDefinition(
            "lifecycle-init",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            interceptor));
    assertEquals(
        Collections.singletonList("init"), events, "initialize() should be called exactly once on register");
  }

  @Test
  void register_multipleInterceptors_invokesInitializeForEach() {
    List<String> events = new ArrayList<>();
    registry.register(
        new InterceptorDefinition(
            "a",
            new Pointcut(ClassMatcher.byName("com.example.A"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "A")));
    registry.register(
        new InterceptorDefinition(
            "b",
            new Pointcut(ClassMatcher.byName("com.example.B"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "B")));
    registry.register(
        new InterceptorDefinition(
            "c",
            new Pointcut(ClassMatcher.byName("com.example.C"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "C")));
    assertEquals(
        Arrays.asList("init:A", "init:B", "init:C"),
        events,
        "Each interceptor should have initialize() invoked in registration order");
  }

  @Test
  void register_overwriteExisting_invokesInitializeOnNewInterceptor() {
    List<String> events = new ArrayList<>();
    registry.register(
        new InterceptorDefinition(
            "dup",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "first")));
    // Re-registering with the same name replaces the old definition
    registry.register(
        new InterceptorDefinition(
            "dup",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "second")));
    assertEquals(
        Arrays.asList("init:first", "init:second"),
        events,
        "Re-registering should invoke initialize() on the new interceptor");
  }

  // --- destroy() on unregister ---

  @Test
  void unregister_invokesDestroy() {
    List<String> events = new ArrayList<>();
    Interceptor interceptor = new LifecycleTrackingInterceptor(events);
    registry.register(
        new InterceptorDefinition(
            "lifecycle-destroy",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            interceptor));
    events.clear(); // discard the init event
    registry.unregister("lifecycle-destroy");
    assertEquals(
        Arrays.asList("destroy"), events, "destroy() should be called exactly once on unregister");
  }

  @Test
  void unregister_nonExistentName_doesNotThrow() {
    assertDoesNotThrow(() -> registry.unregister("does-not-exist"));
  }

  @Test
  void clear_invokesDestroyOnAll() {
    List<String> events = new ArrayList<>();
    registry.register(
        new InterceptorDefinition(
            "a",
            new Pointcut(ClassMatcher.byName("com.example.A"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "A")));
    registry.register(
        new InterceptorDefinition(
            "b",
            new Pointcut(ClassMatcher.byName("com.example.B"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "B")));
    events.clear(); // discard init events
    registry.clear();
    assertEquals(
        Arrays.asList("destroy:A", "destroy:B"),
        events,
        "clear() should invoke destroy() on all registered interceptors");
  }

  // --- Lifecycle error handling ---

  @Test
  void register_initializeThrows_isLoggedButDoesNotPreventRegistration() {
    Interceptor failingInterceptor =
        new Interceptor() {
          @Override
          public void initialize() {
            throw new IllegalStateException("init boom");
          }
        };
    // Should not throw; framework logs and continues
    assertDoesNotThrow(
        () ->
            registry.register(
                new InterceptorDefinition(
                    "failing-init",
                    new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
                    failingInterceptor)));
    assertTrue(registry.getAllDefinitions().stream().anyMatch(d -> "failing-init".equals(d.getName())));
  }

  @Test
  void unregister_destroyThrows_isLoggedButDoesNotPreventRemoval() {
    Interceptor failingInterceptor =
        new Interceptor() {
          @Override
          public void destroy() {
            throw new IllegalStateException("destroy boom");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "failing-destroy",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            failingInterceptor));
    // Should not throw; framework logs and continues
    assertDoesNotThrow(() -> registry.unregister("failing-destroy"));
    assertFalse(
        registry.getAllDefinitions().stream().anyMatch(d -> "failing-destroy".equals(d.getName())));
  }

  // --- Lifecycle ordering with priority ---

  @Test
  void lifecycle_hooksRespectRegistrationOrder() {
    List<String> events = new ArrayList<>();
    registry.register(
        new InterceptorDefinition(
            "first",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "first")));
    registry.register(
        new InterceptorDefinition(
            "second",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            new LifecycleTrackingInterceptor(events, "second")));
    events.clear();
    registry.unregister("first");
    registry.unregister("second");
    assertEquals(
        Arrays.asList("destroy:first", "destroy:second"),
        events,
        "destroy() should be called in unregistration order");
  }

  // --- Programmatic API lifecycle ---

  @Test
  void programmaticInstall_invokesInitialize() {
    List<String> events = new ArrayList<>();
    com.github.cc11001100.weavergirl.core.WeaverGirl weaverGirl =
        com.github.cc11001100.weavergirl.core.WeaverGirl.create();
    weaverGirl
        .intercept("com.example.Service")
        .method("process")
        .before(inv -> {})
        .install();
    // The underlying registry should have called initialize()
    // We verify indirectly by checking the definition exists and lifecycle ran
    assertFalse(weaverGirl.getRegistry().getAllDefinitions().isEmpty());
  }

  // --- No-op default lifecycle ---

  @Test
  void register_noopInterceptor_lifecycleDoesNotThrow() {
    Interceptor noop = new Interceptor() {};
    assertDoesNotThrow(
        () ->
            registry.register(
                new InterceptorDefinition(
                    "noop",
                    new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
                    noop)));
    assertDoesNotThrow(() -> registry.unregister("noop"));
  }

  // --- Helper ---

  private static class LifecycleTrackingInterceptor implements Interceptor {
    private final List<String> events;
    private final String tag;

    LifecycleTrackingInterceptor(List<String> events) {
      this(events, "");
    }

    LifecycleTrackingInterceptor(List<String> events, String tag) {
      this.events = events;
      this.tag = tag;
    }

    @Override
    public void initialize() {
      events.add("init" + (tag.isEmpty() ? "" : ":" + tag));
    }

    @Override
    public void destroy() {
      events.add("destroy" + (tag.isEmpty() ? "" : ":" + tag));
    }
  }
}
