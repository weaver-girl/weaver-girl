package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.context.ContextPropagators;
import com.github.cc11001100.weavergirl.api.context.ContextSnapshot;
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
}
