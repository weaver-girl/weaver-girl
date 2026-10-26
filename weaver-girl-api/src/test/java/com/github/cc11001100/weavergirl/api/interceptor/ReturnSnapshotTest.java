package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A snapshot keeps the value it was given and reports a unique id. */
class ReturnSnapshotTest {

  @Test
  void snapshotKeepsEveryFieldAndPrintsThem() {
    long before = System.nanoTime();
    ReturnSnapshot snapshot = new ReturnSnapshot("value", 3L, "process", String.class);
    long after = System.nanoTime();

    assertTrue(snapshot.getId() > 0);
    assertEquals("value", snapshot.getValue());
    assertEquals(3L, snapshot.getVersion());
    assertTrue(snapshot.getTimestampNanos() >= before);
    assertTrue(snapshot.getTimestampNanos() <= after);
    assertEquals("process", snapshot.getMethodName());
    assertEquals(String.class, snapshot.getReturnType());
    assertTrue(snapshot.toString().contains("method=process"));
    assertTrue(snapshot.toString().contains("value=value"));

    ReturnSnapshot empty = new ReturnSnapshot(null, 1L, "process", Void.class);
    assertNull(empty.getValue());
    assertTrue(empty.getId() > snapshot.getId());
  }
}
