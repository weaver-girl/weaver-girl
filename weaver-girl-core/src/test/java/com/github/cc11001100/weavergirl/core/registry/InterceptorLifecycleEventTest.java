package com.github.cc11001100.weavergirl.core.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.event.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterceptorLifecycleEventTest {

  private DefaultInterceptorRegistry registry;
  private List<InterceptorEvent> events;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    events = new ArrayList<>();
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    for (InterceptorEventListener listener : publisher.getListeners()) {
      publisher.removeListener(listener);
    }
    publisher.addListener(events::add);
  }

  @AfterEach
  void tearDown() {
    registry.clear();
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    for (InterceptorEventListener listener : publisher.getListeners()) {
      publisher.removeListener(listener);
    }
  }

  @Test
  void register_publishesRegistryLifecycleEvent() {
    registry.register(
        new InterceptorDefinition(
            "event-reg",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            new Interceptor() {}));

    assertFalse(events.isEmpty(), "Should publish at least one lifecycle event");
    InterceptorEvent ev = events.get(0);
    assertEquals("weaver-girl.lifecycle.registry", ev.getType());
    assertEquals("event-reg", ev.getPlugin());
    assertEquals(LifecycleEvents.PHASE_REGISTER, ev.getAttributes().get("phase"));
    assertEquals("true", ev.getAttributes().get("success"));
  }

  @Test
  void register_whenInitializeThrows_publishesFailedEvent() {
    Interceptor failing =
        new Interceptor() {
          @Override
          public void initialize() {
            throw new IllegalStateException("init boom");
          }
        };

    registry.register(
        new InterceptorDefinition(
            "failing-event",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            failing));

    assertFalse(events.isEmpty(), "Should still publish lifecycle event on init failure");
    InterceptorEvent ev = events.get(0);
    assertEquals("false", ev.getAttributes().get("success"), "Event should mark init as failed");
  }

  @Test
  void unregister_publishesRegistryLifecycleEvent() {
    registry.register(
        new InterceptorDefinition(
            "event-unreg",
            new Pointcut(ClassMatcher.byName("com.example.Foo"), MethodMatcher.any()),
            new Interceptor() {}));
    events.clear();

    registry.unregister("event-unreg");

    assertFalse(events.isEmpty(), "Unregister should publish lifecycle event");
    InterceptorEvent ev = events.get(0);
    assertEquals("weaver-girl.lifecycle.registry", ev.getType());
    assertEquals(LifecycleEvents.PHASE_UNREGISTER, ev.getAttributes().get("phase"));
    assertEquals("true", ev.getAttributes().get("success"));
  }

  @Test
  void clear_publishesRegistryLifecycleEventForEachInterceptor() {
    registry.register(
        new InterceptorDefinition(
            "clear-a",
            new Pointcut(ClassMatcher.byName("com.example.A"), MethodMatcher.any()),
            new Interceptor() {}));
    registry.register(
        new InterceptorDefinition(
            "clear-b",
            new Pointcut(ClassMatcher.byName("com.example.B"), MethodMatcher.any()),
            new Interceptor() {}));
    events.clear();

    registry.clear();

    List<InterceptorEvent> clearEvents = new ArrayList<>();
    for (InterceptorEvent ev : events) {
      if (LifecycleEvents.PHASE_CLEAR.equals(ev.getAttributes().get("phase"))) {
        clearEvents.add(ev);
      }
    }
    assertEquals(2, clearEvents.size(), "clear() should emit one lifecycle event per interceptor");
    assertEquals("true", clearEvents.get(0).getAttributes().get("success"));
    assertEquals("true", clearEvents.get(1).getAttributes().get("success"));
  }
}
