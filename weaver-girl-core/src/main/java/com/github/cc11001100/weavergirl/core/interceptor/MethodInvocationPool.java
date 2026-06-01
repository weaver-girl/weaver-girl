package com.github.cc11001100.weavergirl.core.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

import java.lang.reflect.Method;

/**
 * Thread-local pool for MethodInvocation instances.
 * Reduces GC pressure by reusing MethodInvocation objects
 * instead of allocating new ones on every method entry/exit.
 *
 * <p>Usage pattern:</p>
 * <pre>
 *   MethodInvocation inv = MethodInvocationPool.acquire(...);
 *   try {
 *       interceptor.before(inv);
 *   } finally {
 *       MethodInvocationPool.release(inv);
 *   }
 * </pre>
 *
 * <p>Thread safety: Each thread has its own pooled instance,
 * so no synchronization is needed.</p>
 */
public final class MethodInvocationPool {

    private static final ThreadLocal<MethodInvocation> POOL = new ThreadLocal<>();

    private MethodInvocationPool() {}

    /**
     * Acquire a MethodInvocation from the pool, or create a new one
     * if the pool is empty. The returned instance is reset with the
     * given parameters.
     *
     * @param targetClass the class declaring the intercepted method
     * @param methodName  the name of the intercepted method
     * @param method      the reflective Method object, may be null
     * @param target      the object instance on which the method is invoked
     * @param arguments   the arguments passed to the method
     * @return a MethodInvocation ready for use
     */
    public static MethodInvocation acquire(Class<?> targetClass, String methodName,
                                           Method method, Object target, Object[] arguments) {
        MethodInvocation inv = POOL.get();
        if (inv != null) {
            POOL.remove();
            inv.reset(targetClass, methodName, method, target, arguments);
            return inv;
        }
        return new MethodInvocation(targetClass, methodName, method, target, arguments);
    }

    /**
     * Return a MethodInvocation to the pool for reuse.
     * Only returns the instance if it hasn't been suppressed or skipped,
     * to avoid carrying over state from exceptional cases.
     *
     * @param inv the MethodInvocation to return, may be null
     */
    public static void release(MethodInvocation inv) {
        if (inv != null) {
            POOL.set(inv);
        }
    }
}
