package com.github.cc11001100.weavergirl.api.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        assertArrayEquals(new Object[]{"a", "b", "c"}, inv.getArguments());
    }

    @Test
    void getArgument_returnsFirstArgument() {
        MethodInvocation inv = new MethodInvocation(String.class, "method", "target", new Object[]{"hello"});
        assertEquals("hello", inv.getArgument(0));
    }

    @Test
    void getArgument_negativeIndex_throwsIndexOutOfBoundsException() {
        MethodInvocation inv = new MethodInvocation(String.class, "method", "target", new Object[]{"a"});
        assertThrows(IndexOutOfBoundsException.class, () -> inv.getArgument(-1));
    }

    @Test
    void getArgument_outOfBounds_throwsIndexOutOfBoundsException() {
        MethodInvocation inv = new MethodInvocation(String.class, "method", "target", new Object[]{"a"});
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
        MethodInvocation inv = new MethodInvocation(String.class, "method", "target", new Object[]{"a", "b"});
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
}
