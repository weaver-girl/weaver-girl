package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class MethodInvocationTest {

  @Test
  void constructor_nullArguments_returnsEmptyArray() {
    MethodInvocation inv = new MethodInvocation(String.class, "trim", "hello", null);
    assertNotNull(inv.getArguments());
    assertEquals(0, inv.getArguments().length);
  }

  @Test
  void constructor_defensiveCopy_mutationDoesNotAffectInvocation() {
    Object[] args = {"first", "second"};
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", args);
    args[0] = "mutated";
    assertEquals("first", inv.getArgument(0));
  }

  @Test
  void getArguments_returnsArray() {
    Object[] args = {"a", "b", "c"};
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", args);
    assertArrayEquals(new Object[] {"a", "b", "c"}, inv.getArguments());
  }

  @Test
  void getArgument_returnsFirstArgument() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"hello"});
    assertEquals("hello", inv.getArgument(0));
  }

  @Test
  void getArgument_negativeIndex_throwsIndexOutOfBoundsException() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a"});
    assertThrows(IndexOutOfBoundsException.class, () -> inv.getArgument(-1));
  }

  @Test
  void getArgument_outOfBounds_throwsIndexOutOfBoundsException() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a"});
    assertThrows(IndexOutOfBoundsException.class, () -> inv.getArgument(5));
  }

  @Test
  void setReturnValue_setsValueAndMarksOverridden() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setReturnValue("result");
    assertEquals("result", inv.getReturnValue());
    assertTrue(inv.isReturnOverridden());
  }

  @Test
  void initReturnValue_setsValueButNotOverridden() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.initReturnValue("original");
    assertEquals("original", inv.getReturnValue());
    assertFalse(inv.isReturnOverridden());
  }

  @Test
  void skipMethod_setsSkipped() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertFalse(inv.isSkipped());
    inv.skipMethod();
    assertTrue(inv.isSkipped());
  }

  @Test
  void setThrowable_andGetThrowable_andHasException() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertFalse(inv.hasException());
    RuntimeException ex = new RuntimeException("test");
    inv.setThrowable(ex);
    assertTrue(inv.hasException());
    assertSame(ex, inv.getThrowable());
  }

  @Test
  void defaultValues_returnValueNull_notSkipped_noException() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertNull(inv.getReturnValue());
    assertFalse(inv.isSkipped());
    assertFalse(inv.hasException());
    assertFalse(inv.isReturnOverridden());
  }

  @Test
  void constructor_nullMethodName_returnsNull() {
    MethodInvocation inv = new MethodInvocation(String.class, null, "target", null);
    assertNull(inv.getMethodName());
  }

  @Test
  void constructor_emptyMethodName_returnsEmpty() {
    MethodInvocation inv = new MethodInvocation(String.class, "", "target", null);
    assertEquals("", inv.getMethodName());
  }

  @Test
  void getArguments_returnsDefensiveCopy() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a", "b"});
    Object[] args = inv.getArguments();
    args[0] = "mutated";
    assertEquals("a", inv.getArgument(0));
  }

  @Test
  void setReturnValue_null_explicitlySetsNull() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setReturnValue("first");
    assertEquals("first", inv.getReturnValue());
    assertTrue(inv.isReturnOverridden());
    inv.setReturnValue(null);
    assertNull(inv.getReturnValue());
    assertTrue(inv.isReturnOverridden());
  }

  @Test
  void suppressException_withReturnValue_exceptionSuppressedAndReturnUsed() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setThrowable(new RuntimeException("boom"));
    inv.setReturnValue("fallback");
    inv.suppressException();
    assertTrue(inv.isExceptionSuppressed());
    assertTrue(inv.hasException());
    assertEquals("fallback", inv.getReturnValue());
    assertTrue(inv.isReturnOverridden());
  }

  @Test
  void isExceptionSuppressed_defaultsFalse() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertFalse(inv.isExceptionSuppressed());
  }

  @Test
  void constructor_withMethodReference_exposesMethod() throws Exception {
    Method m = String.class.getMethod("trim");
    MethodInvocation inv = new MethodInvocation(String.class, "trim", m, "hello", null);
    assertSame(m, inv.getMethod());
    assertSame(String.class, inv.getTargetClass());
    assertEquals("trim", inv.getMethodName());
  }

  @Test
  void getMethod_nullWhenNotProvided() {
    MethodInvocation inv = new MethodInvocation(String.class, "trim", "hello", null);
    assertNull(inv.getMethod());
  }

  @Test
  void getTarget_andSetTarget() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "originalTarget", null);
    assertEquals("originalTarget", inv.getTarget());
    inv.setTarget("newTarget");
    assertEquals("newTarget", inv.getTarget());
  }

  @Test
  void setArgument_updatesArgumentInPlace() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a", "b"});
    inv.setArgument(1, "c");
    assertEquals("c", inv.getArgument(1));
  }

  @Test
  void setArgument_negativeIndex_throwsIndexOutOfBoundsException() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a"});
    assertThrows(IndexOutOfBoundsException.class, () -> inv.setArgument(-1, "x"));
  }

  @Test
  void setArgument_outOfBounds_throwsIndexOutOfBoundsException() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a"});
    assertThrows(IndexOutOfBoundsException.class, () -> inv.setArgument(5, "x"));
  }

  @Test
  void getParameterTypes_withMethod_delegatesToMethod() throws Exception {
    Method m = String.class.getMethod("substring", int.class);
    MethodInvocation inv =
        new MethodInvocation(String.class, "substring", m, "hello", new Object[] {1});
    assertArrayEquals(new Class<?>[] {int.class}, inv.getParameterTypes());
  }

  @Test
  void getParameterTypes_withoutMethod_returnsEmptyArray() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertEquals(0, inv.getParameterTypes().length);
  }

  @Test
  void getReturnType_withMethod_delegatesToMethod() throws Exception {
    Method m = String.class.getMethod("trim");
    MethodInvocation inv = new MethodInvocation(String.class, "trim", m, "hello", null);
    assertEquals(String.class, inv.getReturnType());
  }

  @Test
  void getReturnType_withoutMethod_returnsVoidClass() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertEquals(void.class, inv.getReturnType());
  }

  @Test
  void setSkipMethod_directlySetsSkipFlag() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setSkipMethod(true);
    assertTrue(inv.isSkipped());
    inv.setSkipMethod(false);
    assertFalse(inv.isSkipped());
  }

  // --- Attachment API ---

  @Test
  void attachment_setGetRemoveHas_roundTrip() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertFalse(inv.hasAttachment("startTime"));
    assertNull(inv.getAttachment("startTime"));

    inv.setAttachment("startTime", 42L);
    assertTrue(inv.hasAttachment("startTime"));
    assertEquals(42L, inv.getAttachment("startTime"));
    assertEquals(Long.valueOf(42L), inv.getAttachment("startTime", Long.class));

    Object removed = inv.removeAttachment("startTime");
    assertEquals(42L, removed);
    assertFalse(inv.hasAttachment("startTime"));
    assertNull(inv.getAttachment("startTime"));
  }

  @Test
  void getAttachment_typed_missingKeyReturnsNull() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertNull(inv.getAttachment("missing", String.class));
  }

  @Test
  void removeAttachment_neverSet_returnsNull() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertNull(inv.removeAttachment("missing"));
  }

  @Test
  void setAttachment_secondCall_reusesExistingMap() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setAttachment("a", 1);
    inv.setAttachment("b", 2);
    assertEquals(1, inv.getAttachment("a"));
    assertEquals(2, inv.getAttachment("b"));
  }

  @Test
  void clear_withNoAttachmentsEverSet_doesNotThrow() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.clear();
    assertNull(inv.getAttachment("anything"));
  }

  @Test
  void reset_withNoAttachmentsEverSet_doesNotThrow() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.reset(String.class, "method2", null, "target2", null);
    assertNull(inv.getAttachment("anything"));
  }

  // --- Around-advice chain API ---

  @Test
  void proceed_notProceedable_throwsIllegalStateException() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertFalse(inv.isProceedable());
    assertThrows(IllegalStateException.class, inv::proceed);
    assertFalse(inv.isProceedCalled());
  }

  @Test
  void proceed_whenProceedable_advancesChain() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setProceedable(true);
    assertTrue(inv.isProceedable());
    assertFalse(inv.isProceedCalled());
    inv.proceed();
    assertTrue(inv.isProceedCalled());
  }

  @Test
  void isProceedable_falseWhenDepthReachesLimit() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setProceedable(true);
    for (int i = 0; i < 32; i++) {
      inv.proceed();
    }
    assertFalse(inv.isProceedable());
    assertThrows(IllegalStateException.class, inv::proceed);
  }

  // --- CallSite tracking API ---

  @Test
  void callerInfo_defaultsUnset() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertFalse(inv.hasCaller());
    assertNull(inv.getCallerClass());
    assertNull(inv.getCallerMethodName());
    assertEquals(0, inv.getCallerLineNumber());
  }

  @Test
  void setCaller_populatesCallerInfo() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setCaller(Integer.class, "valueOf", 123);
    assertTrue(inv.hasCaller());
    assertSame(Integer.class, inv.getCallerClass());
    assertEquals("valueOf", inv.getCallerMethodName());
    assertEquals(123, inv.getCallerLineNumber());
  }

  // --- Return snapshot API ---

  @Test
  void returnSnapshot_defaultsNull_andCanBeSet() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertNull(inv.getReturnSnapshot());
    ReturnSnapshot snapshot = new ReturnSnapshot("value", 1L, "method", Object.class);
    inv.setReturnSnapshot(snapshot);
    assertSame(snapshot, inv.getReturnSnapshot());
  }

  // --- Runtime condition support (cflow/if) ---

  @Test
  void condition_defaultsMatchedButNotEvaluated() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertTrue(inv.isConditionMatched());
    assertFalse(inv.isConditionEvaluated());
  }

  @Test
  void setConditionMatched_marksEvaluatedAndStoresResult() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    inv.setConditionMatched(false);
    assertFalse(inv.isConditionMatched());
    assertTrue(inv.isConditionEvaluated());
  }

  // --- pertarget / perthis instance support ---

  @Test
  void perInstance_defaultsAbsent_andCanBeSet() {
    MethodInvocation inv = new MethodInvocation(String.class, "method", "target", null);
    assertFalse(inv.hasPerInstance());
    assertNull(inv.getPerInstance());
    Object state = new Object();
    inv.setPerInstance(state);
    assertTrue(inv.hasPerInstance());
    assertSame(state, inv.getPerInstance());
  }

  // --- reset() lifecycle ---

  @Test
  void reset_restoresDefaultsAndAppliesNewState() throws Exception {
    Method m = String.class.getMethod("trim");
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a"});
    inv.setReturnValue("result");
    inv.setThrowable(new RuntimeException("boom"));
    inv.skipMethod();
    inv.suppressException();
    inv.setCaller(Integer.class, "caller", 7);
    inv.setProceedable(true);
    inv.proceed();
    inv.setReturnSnapshot(new ReturnSnapshot("v", 1L, "method", Object.class));
    inv.setConditionMatched(false);
    inv.setAttachment("k", "v");

    inv.reset(String.class, "trim", m, "newTarget", new Object[] {"x"});

    assertSame(m, inv.getMethod());
    assertEquals("newTarget", inv.getTarget());
    assertEquals("x", inv.getArgument(0));
    assertNull(inv.getReturnValue());
    assertNull(inv.getThrowable());
    assertFalse(inv.isSkipped());
    assertFalse(inv.isReturnOverridden());
    assertFalse(inv.isExceptionSuppressed());
    assertFalse(inv.hasCaller());
    assertEquals(-1, inv.getCallerLineNumber());
    assertFalse(inv.isProceedable());
    assertFalse(inv.isProceedCalled());
    assertNull(inv.getReturnSnapshot());
    assertTrue(inv.isConditionMatched());
    assertFalse(inv.isConditionEvaluated());
    assertNull(inv.getAttachment("k"));
  }

  @Test
  void reset_withNullArguments_resultsInEmptyArguments() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a"});
    inv.reset(String.class, "method2", null, "target2", null);
    assertEquals(0, inv.getArguments().length);
  }

  // --- clear() lifecycle ---

  @Test
  void clear_resetsAllFieldsToDefaults() {
    MethodInvocation inv =
        new MethodInvocation(String.class, "method", "target", new Object[] {"a"});
    inv.setReturnValue("result");
    inv.setThrowable(new RuntimeException("boom"));
    inv.skipMethod();
    inv.suppressException();
    inv.setCaller(Integer.class, "caller", 7);
    inv.setProceedable(true);
    inv.proceed();
    inv.setReturnSnapshot(new ReturnSnapshot("v", 1L, "method", Object.class));
    inv.setConditionMatched(false);
    inv.setAttachment("k", "v");

    inv.clear();

    assertNull(inv.getTargetClass());
    assertNull(inv.getTarget());
    assertNull(inv.getMethod());
    assertNull(inv.getReturnValue());
    assertNull(inv.getThrowable());
    assertFalse(inv.isSkipped());
    assertFalse(inv.isReturnOverridden());
    assertFalse(inv.isExceptionSuppressed());
    assertFalse(inv.hasCaller());
    assertEquals(-1, inv.getCallerLineNumber());
    assertFalse(inv.isProceedable());
    assertFalse(inv.isProceedCalled());
    assertNull(inv.getReturnSnapshot());
    assertTrue(inv.isConditionMatched());
    assertFalse(inv.isConditionEvaluated());
    assertNull(inv.getAttachment("k"));
  }
}
