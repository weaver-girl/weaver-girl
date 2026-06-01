package com.github.cc11001100.weavergirl.api.interceptor;

import java.lang.reflect.Method;

/**
 * Context object passed to {@link Interceptor} callbacks at runtime, encapsulating
 * all information about the intercepted method call.
 *
 * <p>Interceptors can:</p>
 * <ul>
 *   <li>Read the target class, method name, and arguments</li>
 *   <li>Override the return value via {@link #setReturnValue(Object)}</li>
 *   <li>Skip original method execution via {@link #skipMethod()}</li>
 *   <li>Access the thrown exception in {@link Interceptor#onException(MethodInvocation)}</li>
 *   <li>Suppress the thrown exception via {@link #suppressException()}</li>
 * </ul>
 *
 * <h3>Return value override mechanism</h3>
 * <p>Call {@link #setReturnValue(Object)} to override the method's return value. This
 * sets the return value <em>and</em> marks it as overridden ({@link #isReturnOverridden()}
 * returns {@code true}). The framework uses this flag to distinguish between an interceptor-
 * supplied value and the original method return. In contrast, {@link #initReturnValue(Object)}
 * stores the original return value <em>without</em> marking it as overridden; this is an
 * internal framework method and should not be called by interceptors.</p>
 *
 * <h3>Exception suppression mechanism</h3>
 * <p>Call {@link #suppressException()} from {@link Interceptor#onException(MethodInvocation)}
 * to prevent the exception from propagating to the caller. When suppressed, the return value
 * (if set via {@link #setReturnValue(Object)}) or {@code null} will be returned instead.
 * This enables circuit-breaker and fallback patterns.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Each intercepted method invocation creates a fresh {@code MethodInvocation} instance.
 * Within a single method call, callbacks are invoked sequentially (not concurrently) on the
 * same thread that called the target method. Therefore, no synchronization is needed when
 * accessing or mutating a {@code MethodInvocation} within interceptor callbacks.</p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * &#64;Override
 * public void before(MethodInvocation invocation) {
 *     if (invocation.getArgument(0) == null) {
 *         invocation.skipMethod();
 *         invocation.setReturnValue(defaultValue);
 *     }
 * }
 *
 * &#64;Override
 * public void after(MethodInvocation invocation) {
 *     if (invocation.isReturnOverridden()) {
 *         // An interceptor supplied a custom return value
 *     } else {
 *         // The original method returned normally
 *         Object result = invocation.getReturnValue();
 *     }
 * }</pre>
 *
 * @see Interceptor
 * @since 1.0.0
 */
public class MethodInvocation {

    private final Class<?> targetClass;
    private final String methodName;
    private final Method method;
    private final Object target;
    private final Object[] arguments;
    private Object returnValue;
    private Throwable throwable;
    private boolean isSkipped;
    private boolean returnOverridden;
    private boolean exceptionSuppressed;

    /**
     * Constructs a new MethodInvocation.
     *
     * @param targetClass the class declaring the intercepted method
     * @param methodName  the name of the intercepted method
     * @param target      the object instance on which the method is invoked (null for static methods)
     * @param arguments   the arguments passed to the method; defensively copied
     */
    public MethodInvocation(Class<?> targetClass, String methodName,
                            Object target, Object[] arguments) {
        this(targetClass, methodName, null, target, arguments);
    }

    /**
     * Constructs a new MethodInvocation with a {@link Method} reference.
     *
     * @param targetClass the class declaring the intercepted method
     * @param methodName  the name of the intercepted method
     * @param method      the reflective {@link Method} object, may be null
     * @param target      the object instance on which the method is invoked (null for static methods)
     * @param arguments   the arguments passed to the method; defensively copied
     */
    public MethodInvocation(Class<?> targetClass, String methodName,
                            Method method, Object target, Object[] arguments) {
        this.targetClass = targetClass;
        this.methodName = methodName;
        this.method = method;
        this.target = target;
        this.arguments = arguments != null ? arguments.clone() : new Object[0];
        this.isSkipped = false;
    }

    /**
     * Returns the class that declares the intercepted method.
     *
     * @return the target class, never null
     */
    public Class<?> getTargetClass() {
        return targetClass;
    }

    /**
     * Returns the name of the intercepted method.
     *
     * @return the method name, never null
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the reflective {@link Method} object for the intercepted method.
     *
     * <p>This provides access to the full method signature, return type, parameter types,
     * and declared annotations. May return {@code null} if the method reference is not
     * available (e.g., in certain instrumentation contexts).</p>
     *
     * @return the Method object, or null if not available
     */
    public Method getMethod() {
        return method;
    }

    /**
     * Returns the object instance on which the method is invoked.
     *
     * @return the target instance, or null for static methods
     */
    public Object getTarget() {
        return target;
    }

    /**
     * Returns a copy of the arguments passed to the intercepted method.
     *
     * @return the argument array
     */
    public Object[] getArguments() {
        return arguments;
    }

    /**
     * Returns the argument at the specified index.
     *
     * @param index zero-based argument index
     * @return the argument value
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Object getArgument(int index) {
        if (index < 0 || index >= arguments.length) {
            throw new IndexOutOfBoundsException(
                    "Argument index " + index + " out of bounds for " + arguments.length + " arguments");
        }
        return arguments[index];
    }

    /**
     * Returns the parameter types of the intercepted method.
     *
     * <p>This is a convenience method that delegates to {@link Method#getParameterTypes()}.
     * Returns an empty array if the {@link Method} reference is not available.</p>
     *
     * @return the parameter types, or an empty array if the method reference is null
     */
    public Class<?>[] getParameterTypes() {
        return method != null ? method.getParameterTypes() : new Class<?>[0];
    }

    /**
     * Returns the return type of the intercepted method.
     *
     * <p>This is a convenience method that delegates to {@link Method#getReturnType()}.
     * Returns {@code void.class} if the {@link Method} reference is not available.</p>
     *
     * @return the return type, or {@code void.class} if the method reference is null
     */
    public Class<?> getReturnType() {
        return method != null ? method.getReturnType() : void.class;
    }

    /**
     * Returns the return value of the intercepted method.
     *
     * <p>Before the method executes, this returns null. After the method executes,
     * this returns the original return value (set by the framework via
     * {@link #initReturnValue(Object)}) or an overridden value (set by an interceptor
     * via {@link #setReturnValue(Object)}).</p>
     *
     * @return the return value, or null if not yet set or the method returns void
     */
    public Object getReturnValue() {
        return returnValue;
    }

    /**
     * Sets the return value and marks it as overridden by an interceptor.
     *
     * <p>This is the public API for interceptors to override the return value. After
     * calling this method, {@link #isReturnOverridden()} will return {@code true}.</p>
     *
     * @param returnValue the value to return instead of the original method's return
     */
    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
        this.returnOverridden = true;
    }

    /**
     * Internal method for the framework to store the original return value
     * without marking it as overridden.
     *
     * <p>Interceptors should not call this; use {@link #setReturnValue(Object)} instead.</p>
     *
     * @param returnValue the original return value from the intercepted method
     */
    public void initReturnValue(Object returnValue) {
        this.returnValue = returnValue;
    }

    /**
     * Returns the exception thrown by the intercepted method.
     *
     * <p>Only available in the {@link Interceptor#onException(MethodInvocation)} callback.</p>
     *
     * @return the thrown exception, or null if the method completed normally
     */
    public Throwable getThrowable() {
        return throwable;
    }

    /**
     * Sets the exception thrown by the intercepted method.
     *
     * <p>This is called internally by the framework. Interceptors typically read the
     * exception via {@link #getThrowable()} rather than setting it.</p>
     *
     * @param throwable the exception thrown by the intercepted method
     */
    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    /**
     * Returns whether the intercepted method threw an exception.
     *
     * @return true if an exception was thrown
     */
    public boolean hasException() {
        return throwable != null;
    }

    /**
     * Returns whether the original method execution has been skipped.
     *
     * @return true if {@link #skipMethod()} was called
     */
    public boolean isSkipped() {
        return isSkipped;
    }

    /**
     * Sets whether the original method execution should be skipped.
     *
     * <p>This is used internally by the framework for error recovery — if an interceptor
     * calls {@link #skipMethod()} but then throws an exception, the framework resets
     * the skip flag so the method executes normally. Interceptors should prefer
     * {@link #skipMethod()} over calling this directly.</p>
     *
     * @param skip true to skip the original method execution, false to allow it
     */
    public void setSkipMethod(boolean skip) {
        this.isSkipped = skip;
    }

    /**
     * Prevents the original method from executing.
     *
     * <p>After calling this, the framework will not invoke the target method. If a
     * return value is needed, call {@link #setReturnValue(Object)} as well. If no
     * return value is set, null will be returned to the caller.</p>
     */
    public void skipMethod() {
        this.isSkipped = true;
    }

    /**
     * Returns whether an interceptor has explicitly set a return value
     * via {@link #setReturnValue(Object)}.
     *
     * <p>This is useful for distinguishing between an interceptor-supplied value and
     * the original method return. The framework stores original returns via
     * {@link #initReturnValue(Object)}, which does not set this flag.</p>
     *
     * @return true if the return value was set by an interceptor
     */
    public boolean isReturnOverridden() {
        return returnOverridden;
    }

    /**
     * Suppress the exception thrown by the target method.
     * When called from onException(), the exception will not propagate
     * to the caller. Instead, the return value (if set via setReturnValue)
     * or null will be returned.
     */
    public void suppressException() {
        this.exceptionSuppressed = true;
    }

    /**
     * Returns whether the exception has been suppressed via {@link #suppressException()}.
     *
     * @return true if the exception should not propagate to the caller
     */
    public boolean isExceptionSuppressed() {
        return exceptionSuppressed;
    }
}
