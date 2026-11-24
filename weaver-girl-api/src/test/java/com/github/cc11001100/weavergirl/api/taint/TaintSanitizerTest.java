package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A sanitiser hook clears a value after it has proved the value cannot carry a payload. */
class TaintSanitizerTest {

  @BeforeEach
  void open() {
    Taint.openScope();
  }

  @AfterEach
  void close() {
    Taint.closeScope();
  }

  @Test
  void untaint_clearsEveryMarkOnTheValue() {
    String value = new String("payload");
    Taint.tag(value, "http.parameter:q");
    assertTrue(Taint.isTainted(value));
    assertTrue(Taint.untaint(value), "untaint reports that it removed a mark");
    assertFalse(Taint.isTainted(value));
    assertTrue(Taint.sources(value).isEmpty());
    assertFalse(Taint.untaint(value), "a clean value reports no removal");
    System.out.println("IAST sanitiser cleared source=" + Taint.PARAMETER_PREFIX + "q");
  }

  @Test
  void untaint_bySourceLeavesOtherSourcesInPlace() {
    String value = new String("payload");
    Taint.tag(value, "http.parameter:q");
    Taint.tag(value, "http.header:x-forwarded-for");

    assertTrue(Taint.untaint(value, "http.parameter:q"));
    assertTrue(Taint.isTainted(value), "the header source is still present");
    assertFalse(Taint.hasSource(value, "http.parameter:q"));
    assertTrue(Taint.hasSource(value, "http.header:x-forwarded-for"));
    System.out.println("IAST sanitiser kept source=http.header:x-forwarded-for");
  }

  @Test
  void untaint_reachesOuterScopes() {
    String value = new String("payload");
    Taint.tag(value, "http.parameter:q");

    Taint.openScope();
    try {
      assertTrue(Taint.isTainted(value), "an inner scope sees the outer mark");
      assertTrue(Taint.untaint(value));
      assertFalse(Taint.isTainted(value));
    } finally {
      Taint.closeScope();
    }

    assertFalse(Taint.isTainted(value), "the outer scope is clean after the sanitiser ran");
    System.out.println("IAST sanitiser cleared all scopes");
  }

  @Test
  void untaintAllClearsEachAcceptedValueAndSkipsTheCleanOnes() {
    String first = new String("one");
    String second = new String("two");
    String clean = new String("clean");
    Taint.tag(first, "http.parameter:a");
    Taint.tag(second, "http.parameter:b");

    assertEquals(2, Taint.untaintAll(first, second, clean));
    assertFalse(Taint.isTainted(first));
    assertFalse(Taint.isTainted(second));
    assertEquals(0, Taint.untaintAll(null, clean));
    System.out.println("IAST sanitiser cleared a group of 2 values");
  }

  @Test
  void untaint_clearsThePropagatedMarkOnASanitisedResult() {
    String input = new String("dirty");
    Taint.tag(input, "http.parameter:q");

    String encoded = "ENC:".concat(input);
    TaintPropagation.propagateConcat(encoded, "ENC:", input);
    assertTrue(Taint.isTainted(encoded), "the sanitiser output inherited the mark");

    assertTrue(Taint.untaint(encoded));
    assertFalse(Taint.isTainted(encoded));
    assertTrue(Taint.ranges(encoded).isEmpty());
    System.out.println("IAST sanitiser cleared propagated source=" + Taint.PARAMETER_PREFIX + "q");
  }
}
