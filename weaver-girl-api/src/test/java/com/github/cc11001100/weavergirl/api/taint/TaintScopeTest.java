package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.context.ContextPropagators;
import com.github.cc11001100.weavergirl.api.context.ContextSnapshot;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Request-scope tag/query and the shipped context capture/restore path. */
class TaintScopeTest {

  @AfterEach
  void closeLeftoverScopes() {
    for (int i = 0; i < 8; i++) {
      Taint.closeScope();
    }
  }

  @Test
  void tagIsVisibleInScopeAndAbsentAfterCloseAndInANewScope() {
    String value = new String("tracked");
    Taint.openScope();
    Taint.tag(value, "req-a");
    assertTrue(Taint.hasSource(value, "req-a"));
    assertTrue(Taint.isTainted(value));
    Taint.closeScope();
    assertFalse(Taint.isTainted(value), "closed scope must not leak into the thread");

    Taint.openScope();
    assertFalse(Taint.hasSource(value, "req-a"), "a new request scope starts untainted");
    assertFalse(Taint.isTainted(value));
    Taint.closeScope();
    System.out.println("IAST scope source=req-a closed-and-reopened untainted");
  }

  @Test
  void tagOutsideAScopeDoesNotStick() {
    String value = new String("loose");
    Taint.tag(value, "no-scope");
    assertFalse(Taint.isTainted(value));
  }

  @Test
  void captureRestoreShowsTagOnWorkerAndCleanupKeepsWorkerScope() throws Exception {
    final String parentValue = new String("parent-value");
    final String workerValue = new String("worker-value");
    Taint.openScope();
    Taint.tag(parentValue, "parent-source");
    ContextSnapshot parentSnap = ContextPropagators.capture();
    Taint.closeScope();
    assertFalse(Taint.isTainted(parentValue));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(
              new Runnable() {
                @Override
                public void run() {
                  Taint.openScope();
                  Taint.tag(workerValue, "worker-source");
                  assertTrue(Taint.hasSource(workerValue, "worker-source"));
                  ContextPropagators.wrap(
                          new Runnable() {
                            @Override
                            public void run() {
                              assertTrue(
                                  Taint.hasSource(parentValue, "parent-source"),
                                  "restored scope must see the tagging thread's label");
                              assertFalse(Taint.hasSource(workerValue, "worker-source"));
                            }
                          },
                          parentSnap)
                      .run();
                  assertTrue(
                      Taint.hasSource(workerValue, "worker-source"),
                      "cleanup must restore the scope the worker already had");
                  assertFalse(Taint.hasSource(parentValue, "parent-source"));
                  Taint.closeScope();
                  assertFalse(Taint.isTainted(workerValue));
                  assertFalse(Taint.isTainted(parentValue));
                }
              })
          .get(5, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    System.out.println("IAST scope source=parent-source worker-restored source=worker-source");
  }

  @Test
  void wrapCarriesTheScopeIntoTheTaskAndRestoresTheCallerAfterwards() {
    final String value = new String("wrapped-value");
    Taint.openScope();
    Taint.tag(value, "wrapped-source");
    Runnable wrapped =
        Taint.wrap(
            new Runnable() {
              @Override
              public void run() {
                assertTrue(Taint.hasSource(value, "wrapped-source"));
              }
            });
    Taint.closeScope();
    assertFalse(Taint.isTainted(value));

    wrapped.run();
    assertFalse(Taint.isTainted(value), "the previous (empty) scopes are back after the task");
    System.out.println("IAST wrap carried wrapped-source and cleaned up");
  }

  @Test
  void wrapWithoutAScopeAndASecondWrapAreNoOps() {
    Runnable plain = new Runnable() {
      @Override
      public void run() {}
    };
    assertSame(plain, Taint.wrap(plain), "no scope means no wrapper allocation");

    Taint.openScope();
    try {
      Runnable wrapped = Taint.wrap(plain);
      assertNotSame(plain, wrapped);
      assertSame(wrapped, Taint.wrap(wrapped), "an already wrapped task is not wrapped twice");
    } finally {
      Taint.closeScope();
    }
  }

  @Test
  void wrappedCallableSeesTheScopeAndReturnsTheValue() throws Exception {
    final String value = new String("callable-value");
    Taint.openScope();
    Taint.tag(value, "callable-source");
    Callable<Boolean> callable =
        Taint.wrap(
            new Callable<Boolean>() {
              @Override
              public Boolean call() {
                return Boolean.valueOf(Taint.hasSource(value, "callable-source"));
              }
            });
    Taint.closeScope();

    assertTrue(callable.call().booleanValue());
    assertFalse(Taint.isTainted(value));
    System.out.println("IAST callable saw callable-source");
  }

  @Test
  void captureAndRestoreContextMoveTheScopesBetweenThreads() {
    final String value = new String("captured");
    Taint.openScope();
    Taint.tag(value, "captured-source");
    Object snapshot = Taint.captureContext();
    Taint.closeScope();
    assertFalse(Taint.isTainted(value));

    Taint.restoreContext(snapshot);
    assertTrue(Taint.hasSource(value, "captured-source"));
    Taint.restoreContext(null);
    assertFalse(Taint.isTainted(value), "restoring a non-snapshot clears the thread");
    System.out.println("IAST manual capture/restore moved captured-source");
  }
}
