package com.github.cc11001100.weavergirl.api.context;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wrappers activate the snapshot they were given, and wrapping a value that is already wrapped is a
 * no-op so context is never captured twice.
 */
class ContextPropagatorsTest {

  private final ContextSnapshot inner =
      ContextSnapshot.fromThreadContext(
          Collections.<String, Object>singletonMap("traceId", "inner"));

  @AfterEach
  void reset() {
    ThreadContext.clear();
  }

  @Test
  void scopeActivatesANullSnapshotAsEmpty() {
    ThreadContext.put("traceId", "outer");

    try (ContextScope scope = ContextPropagators.scope(null)) {
      assertNotNull(scope);
      assertNull(ThreadContext.get("traceId"));
    }

    assertEquals("outer", ThreadContext.get("traceId"));
  }

  @Test
  void runnableRunsUnderTheCapturedSnapshot() {
    ThreadContext.put("traceId", "inner");
    Runnable wrapped = ContextPropagators.wrap(() -> assertEquals("inner", ThreadContext.get("traceId")));
    ThreadContext.put("traceId", "outer");

    wrapped.run();

    assertEquals("outer", ThreadContext.get("traceId"));
  }

  @Test
  void wrappingAWrappedRunnableReturnsItUnchanged() {
    Runnable wrapped = ContextPropagators.wrap(() -> {}, inner);

    assertSame(wrapped, ContextPropagators.wrap(wrapped, ContextSnapshot.empty()));
  }

  @Test
  void callableReturnsItsValueUnderTheSnapshot() throws Exception {
    Callable<String> wrapped = ContextPropagators.wrap(() -> ThreadContext.get("traceId"), inner);

    assertEquals("inner", wrapped.call());
    assertNull(ThreadContext.get("traceId"));
  }

  @Test
  void wrappingAWrappedCallableReturnsItUnchanged() {
    Callable<String> wrapped = ContextPropagators.wrap(() -> "ok", inner);

    assertSame(wrapped, ContextPropagators.wrap(wrapped, ContextSnapshot.empty()));
  }

  @Test
  void wrapCallablesWrapsEachAndRejectsNull() throws Exception {
    assertNull(ContextPropagators.wrapCallables(null, inner));

    java.util.List<Callable<String>> wrapped =
        ContextPropagators.wrapCallables(
            Collections.singletonList(() -> ThreadContext.get("traceId")), inner);

    assertEquals("inner", wrapped.get(0).call());
  }

  @Test
  void functionAndConsumerRunUnderTheSnapshot() {
    Function<String, String> function =
        ContextPropagators.wrap(
            (Function<String, String>) input -> input + ThreadContext.get("traceId"), inner);
    AtomicReference<String> seen = new AtomicReference<>();
    Consumer<String> consumer =
        ContextPropagators.wrap(
            (Consumer<String>) value -> seen.set(value + ThreadContext.get("traceId")), inner);

    assertEquals("ininner", function.apply("in"));
    consumer.accept("seen");
    assertEquals("seeninner", seen.get());
    assertNull(ThreadContext.get("traceId"));
  }

  @Test
  void wrappingAWrappedFunctionOrConsumerReturnsItUnchanged() {
    Function<String, String> function = ContextPropagators.wrap(input -> input, inner);
    Consumer<String> consumer = ContextPropagators.wrap(value -> {}, inner);

    assertSame(function, ContextPropagators.wrap(function, ContextSnapshot.empty()));
    assertSame(consumer, ContextPropagators.wrap(consumer, ContextSnapshot.empty()));
  }

  @Test
  void biFunctionAndBiConsumerRunUnderTheSnapshot() {
    BiFunction<String, String, String> function =
        ContextPropagators.wrap(
            (BiFunction<String, String, String>)
                (left, right) -> left + right + ThreadContext.get("traceId"),
            inner);
    AtomicReference<String> seen = new AtomicReference<>();
    BiConsumer<String, String> consumer =
        ContextPropagators.wrap(
            (BiConsumer<String, String>)
                (left, right) -> seen.set(left + right + ThreadContext.get("traceId")),
            inner);

    assertEquals("abinner", function.apply("a", "b"));
    consumer.accept("a", "b");
    assertEquals("abinner", seen.get());
  }

  @Test
  void wrappingAWrappedBiFunctionOrBiConsumerReturnsItUnchanged() {
    BiFunction<String, String, String> function =
        ContextPropagators.wrap((BiFunction<String, String, String>) (a, b) -> a, inner);
    BiConsumer<String, String> consumer =
        ContextPropagators.wrap((BiConsumer<String, String>) (a, b) -> {}, inner);

    assertSame(function, ContextPropagators.wrap(function, ContextSnapshot.empty()));
    assertSame(consumer, ContextPropagators.wrap(consumer, ContextSnapshot.empty()));
  }

  @Test
  void captureSeesWhatWasPut() {
    ThreadContext.put("traceId", "inner");

    assertEquals("inner", ContextPropagators.capture().getThreadContext().get("traceId"));
  }

  @Test
  void wrapCallablesKeepsOrder() throws Exception {
    java.util.List<Callable<Integer>> wrapped =
        ContextPropagators.wrapCallables(
            Arrays.<Callable<Integer>>asList(() -> 1, () -> 2), ContextSnapshot.empty());

    assertEquals(Integer.valueOf(1), wrapped.get(0).call());
    assertEquals(Integer.valueOf(2), wrapped.get(1).call());
  }
}
