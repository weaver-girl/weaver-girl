package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The per-instance store keeps one context per target and forgets it on demand. */
class PerInstanceStoreTest {

  @AfterEach
  void tearDown() {
    PerInstanceStore.clear();
  }

  /** The same target returns the same context, and a different target gets its own. */
  @Test
  void eachTargetKeepsItsOwnContext() {
    Object first = new Object();
    Object second = new Object();

    Object context = PerInstanceStore.getOrCreate(first);

    assertSame(context, PerInstanceStore.getOrCreate(first));
    assertSame(PerInstanceStore.getOrCreate(second), PerInstanceStore.getOrCreate(second));
    org.junit.jupiter.api.Assertions.assertNotSame(context, PerInstanceStore.getOrCreate(second));
  }

  /** A static target has no instance, so it gets no context. */
  @Test
  void aNullTargetHasNoContext() {
    assertNull(PerInstanceStore.getOrCreate(null));
  }

  /** Removing a target drops its context, and removing nothing changes nothing. */
  @Test
  void removingATargetDropsItsContext() {
    Object target = new Object();
    Object context = PerInstanceStore.getOrCreate(target);

    PerInstanceStore.remove(null);
    assertSame(context, PerInstanceStore.getOrCreate(target));

    PerInstanceStore.remove(target);
    org.junit.jupiter.api.Assertions.assertNotSame(context, PerInstanceStore.getOrCreate(target));
  }

  /** Clearing forgets every target at once. */
  @Test
  void clearingForgetsEveryTarget() {
    Object target = new Object();
    Object context = PerInstanceStore.getOrCreate(target);

    PerInstanceStore.clear();

    org.junit.jupiter.api.Assertions.assertNotSame(context, PerInstanceStore.getOrCreate(target));
  }
}
