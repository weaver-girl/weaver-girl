package com.github.cc11001100.weavergirl.core.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

import java.lang.reflect.Executable;
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
     * @param methodName  the name of the intercepted method ("<init>" for constructors)
     * @param method      the reflective Method object, may be null
     * @param target      the object instance on which the method is invoked
     * @param arguments   the arguments passed to the method
     * @return a MethodInvocation ready for use
     */
    public static MethodInvocation acquire(Class<?> targetClass, String methodName,
                                           Method method, Object target, Object[] arguments) {
        return acquire(targetClass, methodName, (Executable) method, target, arguments);
    }

    /**
     * Acquire a MethodInvocation from the pool, accepting either a {@link Method}
     * or a {@link java.lang.reflect.Constructor} via the common {@link Executable}
     * supertype. For constructors, {@code methodName} should be {@code "<init>"} and
     * {@code getMethod()} will return null on the resulting invocation.
     *
     * @param targetClass the class declaring the intercepted method or constructor
     * @param methodName  the name of the intercepted method ("<init>" for constructors)
     * @param executable  the reflective Method or Constructor object, may be null
     * @param target      the object instance on which the method is invoked
     * @param arguments   the arguments passed to the method or constructor
     * @return a MethodInvocation ready for use
     */
    public static MethodInvocation acquire(Class<?> targetClass, String methodName,
                                           Executable executable, Object target, Object[] arguments) {
        MethodInvocation inv = POOL.get();
        // For Executable: cast to Method if it is one, else null (constructors
        // don't have a Method representation). MethodInvocation stores Method
        // for backward compatibility; callers use getMethodName() to detect <init>.
        Method method = (executable instanceof Method) ? (Method) executable : null;
        if (inv != null) {
            POOL.remove();
            inv.reset(targetClass, methodName, method, target, arguments);
            return inv;
        }
        return new MethodInvocation(targetClass, methodName, method, target, arguments);
    }

    /**
     * Return a MethodInvocation to the pool for reuse.
     * Clears object references before pooling to prevent memory leaks
     * in idle thread pools (where pooled instances would otherwise retain
     * strong references to target objects and arguments).
     *
     * @param inv the MethodInvocation to return, may be null
     */
    public static void release(MethodInvocation inv) {
        if (inv != null) {
            inv.clear(); // Release strong references to prevent GC retention
            POOL.set(inv);
        }
    }
}
