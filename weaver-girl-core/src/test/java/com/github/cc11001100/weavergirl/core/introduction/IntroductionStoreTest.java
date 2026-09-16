package com.github.cc11001100.weavergirl.core.introduction;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IntroductionStoreTest {

  @AfterEach
  void tearDown() {
    IntroductionStore.clear();
  }

  @Test
  void bindAndGetDelegate_returnsBoundDelegate() {
    Object target = new Object();
    Object delegate = new Object();
    IntroductionStore.bind(target, delegate);
    assertSame(delegate, IntroductionStore.getDelegate(target));
  }

  @Test
  void bind_nullTarget_isNoOp() {
    IntroductionStore.bind(null, new Object());
    assertNull(IntroductionStore.getDelegate(null));
  }

  @Test
  void bind_nullDelegate_isNoOp() {
    Object target = new Object();
    IntroductionStore.bind(target, null);
    assertNull(IntroductionStore.getDelegate(target));
  }

  @Test
  void getDelegate_nullTarget_returnsNull() {
    assertNull(IntroductionStore.getDelegate(null));
  }

  @Test
  void getDelegate_unboundTarget_returnsNull() {
    assertNull(IntroductionStore.getDelegate(new Object()));
  }

  @Test
  void unbind_removesBinding() {
    Object target = new Object();
    IntroductionStore.bind(target, new Object());
    IntroductionStore.unbind(target);
    assertNull(IntroductionStore.getDelegate(target));
  }

  @Test
  void unbind_nullTarget_isNoOp() {
    assertDoesNotThrow(() -> IntroductionStore.unbind(null));
  }

  @Test
  void unbind_unboundTarget_isNoOp() {
    assertDoesNotThrow(() -> IntroductionStore.unbind(new Object()));
  }

  @Test
  void clear_removesAllBindings() {
    Object target1 = new Object();
    Object target2 = new Object();
    IntroductionStore.bind(target1, new Object());
    IntroductionStore.bind(target2, new Object());
    IntroductionStore.clear();
    assertNull(IntroductionStore.getDelegate(target1));
    assertNull(IntroductionStore.getDelegate(target2));
  }

  @Test
  void bind_rebindingSameTarget_overwritesPreviousDelegate() {
    Object target = new Object();
    Object delegate1 = new Object();
    Object delegate2 = new Object();
    IntroductionStore.bind(target, delegate1);
    IntroductionStore.bind(target, delegate2);
    assertSame(delegate2, IntroductionStore.getDelegate(target));
  }
}
