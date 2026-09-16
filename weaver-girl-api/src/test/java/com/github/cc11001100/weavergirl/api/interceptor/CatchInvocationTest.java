package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CatchInvocationTest {

  private static class TargetClass {}

  // --- constructor / basic getters ---

  @Test
  void constructor_setsFields() {
    RuntimeException ex = new RuntimeException("boom");
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", ex);

    assertEquals(TargetClass.class, inv.getTargetClass());
    assertEquals("doWork", inv.getMethodName());
    assertSame(ex, inv.getCaughtException());
  }

  @Test
  void initialState_hasNoOverridesAndNotSuppressed() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    assertFalse(inv.isCatchSuppressed());
    assertFalse(inv.isExceptionOverridden());
    assertFalse(inv.isReturnOverridden());
    assertNull(inv.getReplacementException());
    assertNull(inv.getCatchReturnValue());
  }

  // --- suppressCatch ---

  @Test
  void suppressCatch_setsFlag() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    inv.suppressCatch();

    assertTrue(inv.isCatchSuppressed());
  }

  // --- setCaughtException / replacement ---

  @Test
  void setCaughtException_setsReplacementAndFlag() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException("orig"));
    IllegalStateException replacement = new IllegalStateException("replacement");

    inv.setCaughtException(replacement);

    assertTrue(inv.isExceptionOverridden());
    assertSame(replacement, inv.getReplacementException());
  }

  @Test
  void setCaughtException_secondCallIsIgnored() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException("orig"));
    IllegalStateException first = new IllegalStateException("first");
    IllegalStateException second = new IllegalStateException("second");

    inv.setCaughtException(first);
    inv.setCaughtException(second);

    assertSame(first, inv.getReplacementException());
  }

  @Test
  void setCaughtException_nullArgument_doesNotOverride() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException("orig"));

    inv.setCaughtException(null);

    assertFalse(inv.isExceptionOverridden());
    assertNull(inv.getReplacementException());
  }

  @Test
  void setCaughtException_nullAfterRealValue_doesNotClearExistingOverride() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException("orig"));
    IllegalStateException first = new IllegalStateException("first");

    inv.setCaughtException(first);
    inv.setCaughtException(null);

    assertTrue(inv.isExceptionOverridden());
    assertSame(first, inv.getReplacementException());
  }

  // --- setCatchReturnValue ---

  @Test
  void setCatchReturnValue_setsValueAndFlag() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    inv.setCatchReturnValue("fallback");

    assertTrue(inv.isReturnOverridden());
    assertEquals("fallback", inv.getCatchReturnValue());
  }

  @Test
  void setCatchReturnValue_secondCallIsIgnored() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    inv.setCatchReturnValue("first");
    inv.setCatchReturnValue("second");

    assertEquals("first", inv.getCatchReturnValue());
  }

  @Test
  void setCatchReturnValue_nullValue_stillMarksOverridden() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    inv.setCatchReturnValue(null);

    assertTrue(inv.isReturnOverridden());
    assertNull(inv.getCatchReturnValue());
  }

  // --- attachments ---

  @Test
  void attachments_setAndGet() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    inv.setAttachment("key", "value");

    assertEquals("value", inv.getAttachment("key"));
  }

  @Test
  void attachments_getMissingKey_returnsNull() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    assertNull(inv.getAttachment("missing"));
  }

  @Test
  void attachments_getTyped_castsValue() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    inv.setAttachment("count", 42);

    Integer value = inv.getAttachment("count", Integer.class);
    assertEquals(42, value);
  }

  @Test
  void attachments_getTypedMissingKey_returnsNull() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    assertNull(inv.getAttachment("missing", String.class));
  }

  @Test
  void attachments_removeAttachment_returnsPreviousValueAndClearsIt() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());
    inv.setAttachment("key", "value");

    Object removed = inv.removeAttachment("key");

    assertEquals("value", removed);
    assertNull(inv.getAttachment("key"));
  }

  @Test
  void attachments_removeMissingKey_returnsNull() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    assertNull(inv.removeAttachment("missing"));
  }

  @Test
  void attachments_removeBeforeAnySet_returnsNullWithoutNpe() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    assertDoesNotThrow(() -> assertNull(inv.removeAttachment("key")));
  }

  @Test
  void attachments_setAttachment_nullValueIsAllowed() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    inv.setAttachment("key", null);

    assertNull(inv.getAttachment("key"));
  }

  // --- reset (object pool reuse) ---

  @Test
  void reset_updatesMetadataAndClearsOverridesAndAttachments() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException("orig"));
    inv.suppressCatch();
    inv.setCaughtException(new IllegalStateException("replacement"));
    inv.setCatchReturnValue("fallback");
    inv.setAttachment("key", "value");

    RuntimeException newException = new RuntimeException("new");
    inv.reset(TargetClass.class, "otherMethod", newException);

    assertEquals("otherMethod", inv.getMethodName());
    assertSame(newException, inv.getCaughtException());
    assertFalse(inv.isCatchSuppressed());
    assertFalse(inv.isExceptionOverridden());
    assertFalse(inv.isReturnOverridden());
    assertNull(inv.getReplacementException());
    assertNull(inv.getCatchReturnValue());
    assertNull(inv.getAttachment("key"));
  }

  @Test
  void reset_beforeAnyAttachmentsSet_doesNotThrow() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    assertDoesNotThrow(() -> inv.reset(TargetClass.class, "other", new RuntimeException()));
  }

  @Test
  void reset_allowsReuseAfterwards() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());
    inv.reset(TargetClass.class, "otherMethod", new IllegalArgumentException("x"));

    inv.setCatchReturnValue("value-after-reset");

    assertTrue(inv.isReturnOverridden());
    assertEquals("value-after-reset", inv.getCatchReturnValue());
  }

  // --- clear ---

  @Test
  void clear_nullsAllFieldsAndClearsAttachments() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException("orig"));
    inv.suppressCatch();
    inv.setCaughtException(new IllegalStateException("replacement"));
    inv.setCatchReturnValue("fallback");
    inv.setAttachment("key", "value");

    inv.clear();

    assertNull(inv.getTargetClass());
    assertNull(inv.getMethodName());
    assertNull(inv.getCaughtException());
    assertNull(inv.getReplacementException());
    assertNull(inv.getCatchReturnValue());
    assertFalse(inv.isCatchSuppressed());
    assertFalse(inv.isExceptionOverridden());
    assertFalse(inv.isReturnOverridden());
    assertNull(inv.getAttachment("key"));
  }

  @Test
  void clear_beforeAnyAttachmentsSet_doesNotThrow() {
    CatchInvocation inv = new CatchInvocation(TargetClass.class, "doWork", new RuntimeException());

    assertDoesNotThrow(inv::clear);
  }
}
