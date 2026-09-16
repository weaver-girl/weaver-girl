package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FieldInvocationTest {

  @Test
  void constructor_instanceField_setsMetadataAndNotStatic() {
    Object target = new Object();
    FieldInvocation inv =
        new FieldInvocation(String.class, "value", "java.lang.String", target);

    assertEquals(String.class, inv.getTargetClass());
    assertEquals("value", inv.getFieldName());
    assertEquals("java.lang.String", inv.getFieldTypeName());
    assertSame(target, inv.getTarget());
    assertFalse(inv.isStatic());
  }

  @Test
  void constructor_staticField_nullTarget_isStatic() {
    FieldInvocation inv = new FieldInvocation(String.class, "CONSTANT", "int", null);

    assertNull(inv.getTarget());
    assertTrue(inv.isStatic());
  }

  @Test
  void setReturnValue_firstCallWins_subsequentCallsIgnored() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());

    assertFalse(inv.isReturnOverridden());
    inv.setReturnValue("override1");
    assertTrue(inv.isReturnOverridden());
    assertEquals("override1", inv.getReturnValue());

    inv.setReturnValue("override2");
    assertEquals("override1", inv.getReturnValue(), "Only the first call should have an effect");
  }

  @Test
  void setWriteValue_firstCallWins_subsequentCallsIgnored() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());

    assertFalse(inv.isWriteOverridden());
    inv.setWriteValue("write1");
    assertTrue(inv.isWriteOverridden());
    assertEquals("write1", inv.getWriteValue());

    inv.setWriteValue("write2");
    assertEquals("write1", inv.getWriteValue(), "Only the first call should have an effect");
  }

  @Test
  void skipWrite_setsWriteSkippedFlag() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());

    assertFalse(inv.isWriteSkipped());
    inv.skipWrite();
    assertTrue(inv.isWriteSkipped());
  }

  @Test
  void attachments_setGetRemoveHas() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());

    assertFalse(inv.hasAttachment("k"));
    assertNull(inv.getAttachment("k"));

    inv.setAttachment("k", "v");
    assertTrue(inv.hasAttachment("k"));
    assertEquals("v", inv.getAttachment("k"));
    assertEquals("v", inv.getAttachment("k", String.class));

    Object removed = inv.removeAttachment("k");
    assertEquals("v", removed);
    assertFalse(inv.hasAttachment("k"));
    assertNull(inv.getAttachment("k"));
  }

  @Test
  void getAttachment_typedCast_returnsNullWhenAbsent() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());
    assertNull(inv.getAttachment("missing", Integer.class));
  }

  @Test
  void getAttachment_typedCast_throwsOnWrongType() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());
    inv.setAttachment("k", "a string");
    // The cast inside getAttachment(key, type) is erased at compile time; the ClassCastException
    // only surfaces once the caller assigns the result to a concretely-typed variable.
    assertThrows(
        ClassCastException.class,
        () -> {
          Integer i = inv.getAttachment("k", Integer.class);
          assertNotNull(i);
        });
  }

  @Test
  void removeAttachment_beforeAnySet_returnsNull() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());
    assertNull(inv.removeAttachment("nope"));
  }

  @Test
  void returnSnapshot_setAndGet() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());
    assertNull(inv.getReturnSnapshot());

    ReturnSnapshot snapshot = new ReturnSnapshot("v", 1L, "value", String.class);
    inv.setReturnSnapshot(snapshot);
    assertSame(snapshot, inv.getReturnSnapshot());

    inv.setReturnSnapshot(null);
    assertNull(inv.getReturnSnapshot());
  }

  @Test
  void reset_reinitializesAllStateForPooledReuse() {
    Object oldTarget = new Object();
    FieldInvocation inv = new FieldInvocation(String.class, "old", "java.lang.String", oldTarget);
    inv.setReturnValue("r");
    inv.setWriteValue("w");
    inv.skipWrite();
    inv.setAttachment("k", "v");
    inv.setReturnSnapshot(new ReturnSnapshot("v", 1L, "old", String.class));

    Object newTarget = new Object();
    inv.reset(Integer.class, "newField", "int", newTarget);

    assertEquals(Integer.class, inv.getTargetClass());
    assertEquals("newField", inv.getFieldName());
    assertEquals("int", inv.getFieldTypeName());
    assertSame(newTarget, inv.getTarget());
    assertFalse(inv.isStatic());
    assertNull(inv.getReturnValue());
    assertNull(inv.getWriteValue());
    assertFalse(inv.isReturnOverridden());
    assertFalse(inv.isWriteOverridden());
    assertFalse(inv.isWriteSkipped());
    assertNull(inv.getReturnSnapshot());
    assertFalse(inv.hasAttachment("k"), "Attachments must be cleared on reset");
  }

  @Test
  void reset_toStaticField_setsIsStaticTrue() {
    FieldInvocation inv = new FieldInvocation(String.class, "old", "java.lang.String", new Object());
    inv.reset(String.class, "CONST", "int", null);
    assertTrue(inv.isStatic());
  }

  @Test
  void reset_withNoAttachmentsYetCreated_doesNotThrow() {
    FieldInvocation inv = new FieldInvocation(String.class, "old", "java.lang.String", null);
    assertDoesNotThrow(() -> inv.reset(String.class, "newField", "int", null));
  }

  @Test
  void clear_nullsOutAllReferences() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", new Object());
    inv.setReturnValue("r");
    inv.setWriteValue("w");
    inv.skipWrite();
    inv.setAttachment("k", "v");
    inv.setReturnSnapshot(new ReturnSnapshot("v", 1L, "value", String.class));

    inv.clear();

    assertNull(inv.getTargetClass());
    assertNull(inv.getTarget());
    assertNull(inv.getFieldName());
    assertNull(inv.getFieldTypeName());
    assertNull(inv.getReturnValue());
    assertNull(inv.getWriteValue());
    assertFalse(inv.isReturnOverridden());
    assertFalse(inv.isWriteOverridden());
    assertFalse(inv.isWriteSkipped());
    assertNull(inv.getReturnSnapshot());
    assertFalse(inv.hasAttachment("k"));
  }

  @Test
  void clear_withNoAttachmentsYetCreated_doesNotThrow() {
    FieldInvocation inv = new FieldInvocation(String.class, "value", "java.lang.String", null);
    assertDoesNotThrow(inv::clear);
  }
}
