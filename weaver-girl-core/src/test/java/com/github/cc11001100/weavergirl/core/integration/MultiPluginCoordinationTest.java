package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

/**
 * Integration tests for multiple plugins working together. Verifies that plugins can coordinate
 * through shared registry and event system.
 */
class MultiPluginCoordinationTest {

  private DefaultInterceptorRegistry registry;
  private TestEventListener eventListener;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    eventListener = new TestEventListener();
    InterceptorEventPublisher.getInstance().addListener(eventListener);
  }

  @AfterEach
  void tearDown() {
    registry.clear();
    InterceptorEventPublisher.getInstance().removeListener(eventListener);
  }

  @Test
  @DisplayName("Multiple plugins targeting same class should all execute")
  void multiplePluginsSameClass() {
    List<String> executionOrder = new CopyOnWriteArrayList<>();

    // Plugin A: Logging interceptor for UserService
    Interceptor loggingInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            executionOrder.add("logging-before");
          }

          @Override
          public void after(MethodInvocation inv) {
            executionOrder.add("logging-after");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "logging-userService",
            new Pointcut(ClassMatcher.byName("com.example.UserService"), MethodMatcher.any()),
            loggingInterceptor,
            1));

    // Plugin B: Timing interceptor for UserService
    Interceptor timingInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            executionOrder.add("timing-before");
          }

          @Override
          public void after(MethodInvocation inv) {
            executionOrder.add("timing-after");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "timing-userService",
            new Pointcut(ClassMatcher.byName("com.example.UserService"), MethodMatcher.any()),
            timingInterceptor,
            2));

    // Verify both are registered
    List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.UserService");
    assertEquals(2, defs.size());

    // Verify priority ordering
    assertEquals("logging-userService", defs.get(0).getName());
    assertEquals("timing-userService", defs.get(1).getName());
  }

  @Test
  @DisplayName("Plugins targeting different methods on same class")
  void differentMethodsSameClass() {
    Interceptor getAllInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {}
        };
    Interceptor saveInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {}
        };

    registry.register(
        new InterceptorDefinition(
            "servlet-getAll",
            new Pointcut(
                ClassMatcher.byName("com.example.UserController"), MethodMatcher.byName("getAll")),
            getAllInterceptor,
            0));

    registry.register(
        new InterceptorDefinition(
            "servlet-save",
            new Pointcut(
                ClassMatcher.byName("com.example.UserController"), MethodMatcher.byName("save")),
            saveInterceptor,
            0));

    // getAll should only match getAll
    List<InterceptorDefinition> getAllDefs =
        registry.getInterceptorsForClass("com.example.UserController");
    long getAllMatches =
        getAllDefs.stream()
            .filter(d -> d.getPointcut().getMethodMatcher().matches("getAll"))
            .count();
    assertEquals(1, getAllMatches);

    // save should only match save
    long saveMatches =
        getAllDefs.stream().filter(d -> d.getPointcut().getMethodMatcher().matches("save")).count();
    assertEquals(1, saveMatches);
  }

  @Test
  @DisplayName("Re-registering same interceptor name replaces previous")
  void reRegistrationReplaces() {
    List<String> executions = new CopyOnWriteArrayList<>();

    // First registration
    Interceptor interceptorV1 =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            executions.add("v1");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "my-plugin",
            new Pointcut(ClassMatcher.byName("Target"), MethodMatcher.any()),
            interceptorV1,
            0));

    // Second registration with same name
    Interceptor interceptorV2 =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            executions.add("v2");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "my-plugin",
            new Pointcut(ClassMatcher.byName("Target"), MethodMatcher.any()),
            interceptorV2,
            0));

    // Should only have one definition
    List<InterceptorDefinition> defs = registry.getInterceptorsForClass("Target");
    assertEquals(1, defs.size());

    // Should be the new one
    defs.get(0).getInterceptor().before(null);
    assertEquals(Collections.singletonList("v2"), executions);
  }

  @Test
  @DisplayName("Events from multiple plugins are delivered to shared listener")
  void sharedEventSystem() {
    // Publish events as if from different plugins
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder().type("jdbc-query").plugin("jdbc").className("Stmt").build());
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("http-request")
                .plugin("servlet")
                .className("HttpServlet")
                .build());
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("redis-cmd")
                .plugin("redis")
                .className("Jedis")
                .build());

    assertEquals(3, eventListener.events.size());
    assertEquals("jdbc-query", eventListener.events.get(0).getType());
    assertEquals("http-request", eventListener.events.get(1).getType());
    assertEquals("redis-cmd", eventListener.events.get(2).getType());
  }

  @Test
  @DisplayName("Registry unregister removes interceptor correctly")
  void unregisterRemovesInterceptor() {
    Interceptor interceptor = new Interceptor() {};
    registry.register(
        new InterceptorDefinition(
            "temp-plugin",
            new Pointcut(ClassMatcher.byName("Target"), MethodMatcher.any()),
            interceptor,
            0));

    assertEquals(1, registry.getInterceptorsForClass("Target").size());

    boolean removed = registry.unregister("temp-plugin");
    assertTrue(removed);
    assertEquals(0, registry.getInterceptorsForClass("Target").size());

    // Second unregister should return false
    assertFalse(registry.unregister("temp-plugin"));
  }

  /** Test event listener that collects all events for verification. */
  static class TestEventListener implements InterceptorEventListener {
    final List<InterceptorEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(InterceptorEvent event) {
      events.add(event);
    }
  }
}
