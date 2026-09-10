package com.github.cc11001100.weavergirl.core.metrics;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MicrometerBridgeTest {

  private MeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    MicrometerBridge.start(registry);
  }

  @AfterEach
  void tearDown() {
    MicrometerBridge.stop();
  }

  @Test
  void startsAndReportsTransformationCounters() {
    InterceptorEventPublisher.getInstance().publish(
        InterceptorEvent.builder()
            .type("transformation")
            .plugin("test")
            .className("com.example.Foo")
            .methodName("bar")
            .build());

    Counter counter = registry.find("weavergirl.transformations.total").counter();
    assertNotNull(counter);
    assertEquals(1.0, counter.count(), 0.0);
  }

  @Test
  void recordsInvocationAndHookTimers() {
    InterceptorEventPublisher.getInstance().publish(
        InterceptorEvent.builder()
            .type("interceptor")
            .plugin("test")
            .className("com.example.Foo")
            .methodName("process")
            .durationMs(12)
            .build());

    Counter invocation = registry.find("weavergirl.interceptor.invocations.total").counter();
    assertNotNull(invocation);
    assertEquals(1.0, invocation.count(), 0.0);

    Timer timer = registry.find("weavergirl.interceptor.hook").tag("name", "process").timer();
    assertNotNull(timer);
    assertEquals(1, timer.count());
    assertEquals(12, timer.totalTime(TimeUnit.MILLISECONDS), 0.0);
  }

  @Test
  void recordsInterceptorErrors() {
    InterceptorEventPublisher.getInstance().publish(
        InterceptorEvent.builder()
            .type("interceptor.error")
            .plugin("test")
            .className("com.example.Foo")
            .methodName("bad")
            .build());

    Counter errors = registry.find("weavergirl.interceptor.errors.total").counter();
    assertNotNull(errors);
    assertEquals(1.0, errors.count(), 0.0);
  }

  @Test
  void ignoresNullRegistry() {
    MicrometerBridge.stop();
    assertFalse(MicrometerBridge.isStarted());
    MicrometerBridge.start(null);
    assertFalse(MicrometerBridge.isStarted());
  }
}
