package com.github.cc11001100.weavergirl.core.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MethodInvocationPoolTest {

    private static class DummyTarget {
        @SuppressWarnings("unused")
        public String greet(String name) { return "hello " + name; }
    }

    private Method getGreetMethod() throws NoSuchMethodException {
        return DummyTarget.class.getMethod("greet", String.class);
    }

    @Test
    void acquireReturnsMethodInvocationWithCorrectFields() throws NoSuchMethodException {
        Method method = getGreetMethod();
        Object target = new DummyTarget();
        Object[] args = {"world"};

        MethodInvocation inv = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, target, args);

        assertEquals(DummyTarget.class, inv.getTargetClass());
        assertEquals("greet", inv.getMethodName());
        assertEquals(method, inv.getMethod());
        assertSame(target, inv.getTarget());
        assertEquals("world", inv.getArgument(0));
        assertFalse(inv.isSkipped());
        assertFalse(inv.isReturnOverridden());
        assertFalse(inv.isExceptionSuppressed());
        assertNull(inv.getReturnValue());
        assertNull(inv.getThrowable());

        // Release so we don't pollute the thread-local pool for other tests
        MethodInvocationPool.release(inv);
    }

    @Test
    void acquireAfterReleaseReusesSameInstance() throws NoSuchMethodException {
        Method method = getGreetMethod();
        Object target = new DummyTarget();

        MethodInvocation first = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, target, new Object[]{"a"});
        MethodInvocationPool.release(first);

        MethodInvocation second = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, target, new Object[]{"b"});
        // Must be the exact same object reused from the pool
        assertSame(first, second);
        // Fields must be reset to the new arguments
        assertEquals("b", second.getArgument(0));

        MethodInvocationPool.release(second);
    }

    @Test
    void acquireWithoutReleaseCreatesNewInstance() throws NoSuchMethodException {
        Method method = getGreetMethod();
        Object target = new DummyTarget();

        // Acquire but do NOT release
        MethodInvocation first = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, target, new Object[]{"x"});

        // Acquire again — pool is empty, so a new instance must be created
        MethodInvocation second = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, target, new Object[]{"y"});
        assertNotSame(first, second);
        assertEquals("y", second.getArgument(0));

        // Clean up
        MethodInvocationPool.release(first);
        MethodInvocationPool.release(second);
    }

    @Test
    void releaseWithNullDoesNotThrow() {
        // Should be a no-op, not an exception
        assertDoesNotThrow(() -> MethodInvocationPool.release(null));
    }

    @Test
    void resetClearsState() throws NoSuchMethodException {
        Method method = getGreetMethod();
        Object target = new DummyTarget();

        MethodInvocation inv = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, target, new Object[]{"original"});

        // Mutate state as interceptors would
        inv.skipMethod();
        inv.setReturnValue("overridden");
        inv.setThrowable(new RuntimeException("boom"));
        inv.suppressException();

        assertTrue(inv.isSkipped());
        assertTrue(inv.isReturnOverridden());
        assertTrue(inv.hasException());
        assertTrue(inv.isExceptionSuppressed());

        // Release and re-acquire (triggers reset)
        MethodInvocationPool.release(inv);
        MethodInvocation reused = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, target, new Object[]{"fresh"});

        assertSame(inv, reused);
        assertFalse(reused.isSkipped());
        assertFalse(reused.isReturnOverridden());
        assertFalse(reused.hasException());
        assertFalse(reused.isExceptionSuppressed());
        assertNull(reused.getReturnValue());
        assertNull(reused.getThrowable());
        assertEquals("fresh", reused.getArgument(0));

        MethodInvocationPool.release(reused);
    }

    @Test
    void acquireWithNullArgumentsUsesEmptyArray() throws NoSuchMethodException {
        Method method = getGreetMethod();
        MethodInvocation inv = MethodInvocationPool.acquire(
                DummyTarget.class, "greet", method, null, null);

        assertEquals(0, inv.getArguments().length);

        MethodInvocationPool.release(inv);
    }
}
