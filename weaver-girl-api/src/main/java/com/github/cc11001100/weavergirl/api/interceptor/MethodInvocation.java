package com.github.cc11001100.weavergirl.api.interceptor;

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
    private final Object target;
    private final Object[] arguments;
    private Object returnValue;
    private Throwable throwable;
    private boolean isSkipped;
    private boolean returnOverridden;

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
        this.targetClass = targetClass;
        this.methodName = methodName;
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
}
