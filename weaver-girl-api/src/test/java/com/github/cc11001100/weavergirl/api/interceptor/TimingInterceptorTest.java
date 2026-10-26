package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Timing only publishes when a call is slow or throws, and never across threads. */
class TimingInterceptorTest {

  private final List<InterceptorEvent> events = new ArrayList<>();
  private final InterceptorEventListener listener = events::add;

  @BeforeEach
  void listen() {
    InterceptorEventPublisher.getInstance().addListener(listener);
  }

  @AfterEach
  void silence() {
    InterceptorEventPublisher.getInstance().removeListener(listener);
  }

  @Test
  void aFastCallPublishesNothing() {
    TimingInterceptor interceptor = interceptor(60_000);

    interceptor.before(invocation(null));
    interceptor.after(invocation(null));

    assertTrue(events.isEmpty());
  }

  @Test
  void aSlowCallPublishesItsDuration() {
    TimingInterceptor interceptor = interceptor(0);

    interceptor.before(invocation(null));
    interceptor.after(invocation(null));

    assertEquals(1, events.size());
    assertEquals("slow", events.get(0).getType());
    assertEquals("orders", events.get(0).getPlugin());
    assertTrue(events.get(0).getDurationMs() >= 0);
  }

  @Test
  void anExceptionPublishesTheErrorAndClearsTheTimer() {
    TimingInterceptor interceptor = interceptor(0);
    interceptor.before(invocation(null));

    interceptor.onException(invocation(new IllegalStateException("db down")));

    assertEquals(1, events.size());
    assertEquals("error", events.get(0).getType());
    assertEquals("db down", events.get(0).getAttributes().get("error"));

    interceptor.after(invocation(null));
    assertEquals(1, events.size(), "the timer must not survive the exception");
  }

  @Test
  void anExceptionWithoutAThrowableStillPublishes() {
    TimingInterceptor interceptor = interceptor(0);

    interceptor.onException(invocation(null));

    assertEquals("", events.get(0).getAttributes().get("error"));
  }

  @Test
  void afterWithoutBeforeRecordsNothing() {
    interceptor(0).after(invocation(null));

    assertTrue(events.isEmpty());
  }

  @Test
  void thresholdIsExposed() {
    assertEquals(250, interceptor(250).getThresholdMs());
  }

  private static TimingInterceptor interceptor(long thresholdMs) {
    return new TimingInterceptor("orders", "slow", "error", thresholdMs);
  }

  private static MethodInvocation invocation(Throwable thrown) {
    MethodInvocation invocation =
        new MethodInvocation(TimingInterceptorTest.class, "place", null, new Object[0]);
    invocation.setThrowable(thrown);
    return invocation;
  }
}
