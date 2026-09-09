package com.github.cc11001100.weavergirl.api.interceptor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Context object passed to {@link Interceptor} callbacks at runtime, encapsulating all information
 * about the intercepted method call.
 *
 * <p>Interceptors can:
 *
 * <ul>
 *   <li>Read the target class, method name, and arguments
 *   <li>Override the return value via {@link #setReturnValue(Object)}
 *   <li>Skip original method execution via {@link #skipMethod()}
 *   <li>Access the thrown exception in {@link Interceptor#onException(MethodInvocation)}
 *   <li>Suppress the thrown exception via {@link #suppressException()}
 * </ul>
 *
 * <h3>Return value override mechanism</h3>
 *
 * <p>Call {@link #setReturnValue(Object)} to override the method's return value. This sets the
 * return value <em>and</em> marks it as overridden ({@link #isReturnOverridden()} returns {@code
 * true}). The framework uses this flag to distinguish between an interceptor- supplied value and
 * the original method return. In contrast, {@link #initReturnValue(Object)} stores the original
 * return value <em>without</em> marking it as overridden; this is an internal framework method and
 * should not be called by interceptors.
 *
 * <h3>Exception suppression mechanism</h3>
 *
 * <p>Call {@link #suppressException()} from {@link Interceptor#onException(MethodInvocation)} to
 * prevent the exception from propagating to the caller. When suppressed, the return value (if set
 * via {@link #setReturnValue(Object)}) or {@code null} will be returned instead. This enables
 * circuit-breaker and fallback patterns.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Each intercepted method invocation uses a {@code MethodInvocation} instance that may be
 * obtained from a thread-local object pool. Within a single method call, callbacks are invoked
 * sequentially (not concurrently) on the same thread that called the target method. Therefore, no
 * synchronization is needed when accessing or mutating a {@code MethodInvocation} within
 * interceptor callbacks.
 *
 * <h3>Usage example</h3>
 *
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

  private Class<?> targetClass;
  private String methodName;
  private Method method;
  private Object target;
  private Object[] arguments;
  private Object returnValue;
  private Throwable throwable;
  private boolean isSkipped;
  private boolean returnOverridden;
  private boolean exceptionSuppressed;
  private Map<String, Object> attachments;

  // CallSite tracking: who called the intercepted method
  private Class<?> callerClass;
  private String callerMethodName;
  private int callerLineNumber;

  // Around-advice chain state
  private volatile boolean proceedable;
  private volatile int proceedDepth;
  private volatile boolean proceedCalled;
  private static final int MAX_PROCEED_DEPTH = 32;

  // Return value snapshot for versioning
  private com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot returnSnapshot;

  /**
   * Constructs a new MethodInvocation.
   *
   * @param targetClass the class declaring the intercepted method
   * @param methodName the name of the intercepted method
   * @param target the object instance on which the method is invoked (null for static methods)
   * @param arguments the arguments passed to the method; defensively copied
   */
  public MethodInvocation(
      Class<?> targetClass, String methodName, Object target, Object[] arguments) {
    this(targetClass, methodName, null, target, arguments);
  }

  /**
   * Constructs a new MethodInvocation with a {@link Method} reference.
   *
   * @param targetClass the class declaring the intercepted method
   * @param methodName the name of the intercepted method
   * @param method the reflective {@link Method} object, may be null
   * @param target the object instance on which the method is invoked (null for static methods)
   * @param arguments the arguments passed to the method; defensively copied
   */
  public MethodInvocation(
      Class<?> targetClass, String methodName, Method method, Object target, Object[] arguments) {
    this.targetClass = targetClass;
    this.methodName = methodName;
    this.method = method;
    this.target = target;
    this.arguments = arguments != null ? arguments.clone() : new Object[0];
    this.isSkipped = false;
  }

  /**
   * Reset this instance for reuse from the object pool. This is an internal framework method
   * &mdash; interceptors should not call this.
   *
   * @param targetClass the class declaring the intercepted method
   * @param methodName the name of the intercepted method
   * @param method the reflective {@link Method} object, may be null
   * @param target the object instance on which the method is invoked (null for static methods)
   * @param arguments the arguments passed to the method; defensively copied
   */
  public void reset(
      Class<?> targetClass, String methodName, Method method, Object target, Object[] arguments) {
    this.targetClass = targetClass;
    this.methodName = methodName;
    this.method = method;
    this.target = target;
    this.arguments = arguments != null ? arguments.clone() : new Object[0];
    this.returnValue = null;
    this.throwable = null;
    this.isSkipped = false;
    this.returnOverridden = false;
    this.exceptionSuppressed = false;
    this.callerClass = null;
    this.callerMethodName = null;
    this.callerLineNumber = -1;
    this.proceedable = false;
    this.proceedDepth = 0;
    this.proceedCalled = false;
    this.returnSnapshot = null;
    if (this.attachments != null) {
      this.attachments.clear();
    }
  }

  /**
   * Clear all object references to prevent memory leaks when pooled. Called when returning to the
   * pool to avoid retaining strong references to target objects and arguments in idle thread pools.
   */
  public void clear() {
    this.targetClass = null;
    this.target = null;
    this.arguments = null;
    this.method = null;
    this.returnValue = null;
    this.throwable = null;
    this.isSkipped = false;
    this.returnOverridden = false;
    this.exceptionSuppressed = false;
    this.callerClass = null;
    this.callerMethodName = null;
    this.callerLineNumber = -1;
    this.proceedable = false;
    this.proceedDepth = 0;
    this.proceedCalled = false;
    this.returnSnapshot = null;
    if (this.attachments != null) {
      this.attachments.clear();
    }
  }

  // --- Attachment API (state passing between @Before and @After) ---

  /**
   * Stores an attachment value identified by the given key.
   *
   * <p>Attachments allow state to be passed between interceptor callbacks. For example, a
   * {@code @Before} advice can store a start timestamp, and the corresponding {@code @After} advice
   * can retrieve it to compute the elapsed time.
   *
   * <h3>Example:</h3>
   *
   * <pre>
   * // In @Before:
   * invocation.setAttachment("startTime", System.nanoTime());
   *
   * // In @After:
   * Long start = (Long) invocation.getAttachment("startTime");
   * long elapsedNanos = System.nanoTime() - start;</pre>
   *
   * @param key the attachment key
   * @param value the attachment value (may be null)
   * @see #getAttachment(String)
   * @see #removeAttachment(String)
   * @since 1.4.0
   */
  public void setAttachment(String key, Object value) {
    if (this.attachments == null) {
      this.attachments = new HashMap<>();
    }
    this.attachments.put(key, value);
  }

  /**
   * Retrieves an attachment value by key.
   *
   * @param key the attachment key
   * @return the attachment value, or {@code null} if not found
   * @see #setAttachment(String, Object)
   * @since 1.4.0
   */
  public Object getAttachment(String key) {
    return this.attachments != null ? this.attachments.get(key) : null;
  }

  /**
   * Retrieves an attachment value by key, with a typed convenience cast.
   *
   * @param key the attachment key
   * @param type the expected type
   * @param <T> the expected type
   * @return the attachment value cast to the expected type, or {@code null} if not found
   * @throws ClassCastException if the value is not of the expected type
   * @since 1.4.0
   */
  @SuppressWarnings("unchecked")
  public <T> T getAttachment(String key, Class<T> type) {
    Object value = getAttachment(key);
    return value != null ? (T) value : null;
  }

  /**
   * Removes an attachment value by key.
   *
   * @param key the attachment key
   * @return the previous value associated with the key, or {@code null}
   * @since 1.4.0
   */
  public Object removeAttachment(String key) {
    return this.attachments != null ? this.attachments.remove(key) : null;
  }

  /**
   * Returns whether an attachment with the given key exists.
   *
   * @param key the attachment key
   * @return true if an attachment with this key exists
   * @since 1.4.0
   */
  public boolean hasAttachment(String key) {
    return this.attachments != null && this.attachments.containsKey(key);
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
   * <p>This provides access to the full method signature, return type, parameter types, and
   * declared annotations. May return {@code null} if the method reference is not available (e.g.,
   * in certain instrumentation contexts).
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
   * Update the target object reference. This is an internal framework method used by the
   * constructor advice to patch the target after construction completes (in {@code onMethodEnter}
   * the target is null because the object has not yet been created; in {@code onMethodExit} it is
   * the constructed object). Interceptors should not call this.
   *
   * @param target the constructed object, or null for static methods
   */
  public void setTarget(Object target) {
    this.target = target;
  }

  /**
   * Returns a copy of the arguments passed to the intercepted method.
   *
   * @return the argument array
   */
  public Object[] getArguments() {
    return arguments.clone();
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
   * Set the argument at the specified index. This modifies the actual arguments array (not a
   * clone), so the change is visible to subsequent interceptors and (for around-advice) to the
   * target method itself.
   *
   * @param index zero-based argument index
   * @param value the new argument value
   * @throws IndexOutOfBoundsException if the index is out of range
   */
  public void setArgument(int index, Object value) {
    if (index < 0 || index >= arguments.length) {
      throw new IndexOutOfBoundsException(
          "Argument index " + index + " out of bounds for " + arguments.length + " arguments");
    }
    arguments[index] = value;
  }

  /**
   * Returns the parameter types of the intercepted method.
   *
   * <p>This is a convenience method that delegates to {@link Method#getParameterTypes()}. Returns
   * an empty array if the {@link Method} reference is not available.
   *
   * @return the parameter types, or an empty array if the method reference is null
   */
  public Class<?>[] getParameterTypes() {
    return method != null ? method.getParameterTypes() : new Class<?>[0];
  }

  /**
   * Returns the return type of the intercepted method.
   *
   * <p>This is a convenience method that delegates to {@link Method#getReturnType()}. Returns
   * {@code void.class} if the {@link Method} reference is not available.
   *
   * @return the return type, or {@code void.class} if the method reference is null
   */
  public Class<?> getReturnType() {
    return method != null ? method.getReturnType() : void.class;
  }

  /**
   * Returns the return value of the intercepted method.
   *
   * <p>Before the method executes, this returns null. After the method executes, this returns the
   * original return value (set by the framework via {@link #initReturnValue(Object)}) or an
   * overridden value (set by an interceptor via {@link #setReturnValue(Object)}).
   *
   * @return the return value, or null if not yet set or the method returns void
   */
  public Object getReturnValue() {
    return returnValue;
  }

  /**
   * Sets the return value and marks it as overridden by an interceptor.
   *
   * <p>This is the public API for interceptors to override the return value. After calling this
   * method, {@link #isReturnOverridden()} will return {@code true}.
   *
   * <p><strong>Conflict resolution:</strong> when multiple interceptors call this method, the
   * <em>last</em> call wins. Because interceptors execute in priority order, this means the
   * highest-priority interceptor that runs last determines the final return value.
   *
   * @param returnValue the value to return instead of the original method's return
   */
  public void setReturnValue(Object returnValue) {
    this.returnValue = returnValue;
    this.returnOverridden = true;
  }

  /**
   * Internal method for the framework to store the original return value without marking it as
   * overridden.
   *
   * <p>Interceptors should not call this; use {@link #setReturnValue(Object)} instead.
   *
   * @param returnValue the original return value from the intercepted method
   */
  public void initReturnValue(Object returnValue) {
    this.returnValue = returnValue;
  }

  /**
   * Returns the exception thrown by the intercepted method.
   *
   * <p>Only available in the {@link Interceptor#onException(MethodInvocation)} callback.
   *
   * @return the thrown exception, or null if the method completed normally
   */
  public Throwable getThrowable() {
    return throwable;
  }

  /**
   * Sets the exception thrown by the intercepted method.
   *
   * <p>This is called internally by the framework. Interceptors typically read the exception via
   * {@link #getThrowable()} rather than setting it.
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
   * <p>This is used internally by the framework for error recovery — if an interceptor calls {@link
   * #skipMethod()} but then throws an exception, the framework resets the skip flag so the method
   * executes normally. Interceptors should prefer {@link #skipMethod()} over calling this directly.
   *
   * @param skip true to skip the original method execution, false to allow it
   */
  public void setSkipMethod(boolean skip) {
    this.isSkipped = skip;
  }

  /**
   * Prevents the original method from executing.
   *
   * <p>After calling this, the framework will not invoke the target method. If a return value is
   * needed, call {@link #setReturnValue(Object)} as well. If no return value is set, null will be
   * returned to the caller.
   */
  public void skipMethod() {
    this.isSkipped = true;
  }

  /**
   * Returns whether an interceptor has explicitly set a return value via {@link
   * #setReturnValue(Object)}.
   *
   * <p>This is useful for distinguishing between an interceptor-supplied value and the original
   * method return. The framework stores original returns via {@link #initReturnValue(Object)},
   * which does not set this flag.
   *
   * @return true if the return value was set by an interceptor
   */
  public boolean isReturnOverridden() {
    return returnOverridden;
  }

  /**
   * Suppress the exception thrown by the target method. When called from onException(), the
   * exception will not propagate to the caller. Instead, the return value (if set via
   * setReturnValue) or null will be returned.
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

  // --- Around-advice chain API ---

  /**
   * Mark this invocation as proceedable. Called by the framework before around-advice callbacks so
   * interceptors may continue the chain.
   *
   * <p>This is an internal framework method; interceptors should not call it directly.
   *
   * @since 1.6.0
   */
  public void setProceedable(boolean proceedable) {
    this.proceedable = proceedable;
  }

  /**
   * Returns whether this invocation can proceed through the around-advice chain.
   *
   * <p>Returns {@code false} if the maximum proceed depth has been reached, protecting against
   * runaway around chains.
   *
   * @return true if proceeding is allowed
   * @since 1.6.0
   */
  public boolean isProceedable() {
    return proceedable && proceedDepth < MAX_PROCEED_DEPTH;
  }

  /**
   * Returns whether {@link #proceed()} was called during the current around-advice chain.
   *
   * <p>This can be used by the framework or advanced interceptors to detect whether an interceptor
   * actually invoked proceed, as opposed to merely having proceedable state set.
   *
   * @return true if proceed has been called at least once
   * @since 1.6.0
   */
  public boolean isProceedCalled() {
    return proceedCalled;
  }

  /**
   * Advance the around-advice chain to the next interceptor or the original method. Each call
   * increments an internal depth counter to guard against infinite around recursion.
   *
   * <p>Only the <strong>first</strong> call advances the chain; subsequent calls throw {@link
   * IllegalStateException}. This enforces the standard AOP contract that around advice calls {@code
   * proceed()} exactly once.
   *
   * <p>Throws {@link IllegalStateException} if proceeding is not allowed, if the depth limit is
   * exceeded, or if the chain has already been exhausted.
   *
   * @throws IllegalStateException if the invocation cannot proceed
   * @since 1.6.0
   */
  public void proceed() {
    if (!isProceedable()) {
      throw new IllegalStateException(
          "MethodInvocation cannot proceed: proceedable=" + proceedable + ", depth=" + proceedDepth);
    }
    proceedDepth++;
    proceedCalled = true;
    proceedable = false;
  }

  // --- CallSite tracking API ---

  /**
   * Sets the caller information for this invocation. This is an internal framework method called by
   * the advice at method entry time; interceptors should not call this.
   *
   * @param callerClass the class that called the intercepted method, may be null
   * @param callerMethodName the name of the calling method, may be null
   * @param callerLineNumber the source line number of the call site, or -1 if unknown
   * @since 1.5.0
   */
  public void setCaller(Class<?> callerClass, String callerMethodName, int callerLineNumber) {
    this.callerClass = callerClass;
    this.callerMethodName = callerMethodName;
    this.callerLineNumber = callerLineNumber;
  }

  /**
   * Returns the class that invoked the intercepted method.
   *
   * <p>This is derived from the call stack at method entry time. May return {@code null} if the
   * caller class could not be determined (e.g., the call originated from JNI or the JDK internals).
   *
   * @return the caller class, or null if unknown
   * @since 1.5.0
   */
  public Class<?> getCallerClass() {
    return callerClass;
  }

  /**
   * Returns the name of the method that invoked the intercepted method.
   *
   * @return the caller method name, or null if unknown
   * @since 1.5.0
   */
  public String getCallerMethodName() {
    return callerMethodName;
  }

  /**
   * Returns the source line number at which the intercepted method was called.
   *
   * @return the line number, or -1 if unknown
   * @since 1.5.0
   */
  public int getCallerLineNumber() {
    return callerLineNumber;
  }

  /**
   * Returns whether caller information is available.
   *
   * @return true if at least the caller class is known
   * @since 1.5.0
   */
  public boolean hasCaller() {
    return callerClass != null;
  }

  /**
   * Sets the return snapshot for this invocation. This is an internal framework method used by the
   * exit advice to attach a {@link com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot}
   * to the invocation. Interceptors should not call this.
   *
   * @param snapshot the return snapshot, or null
   * @since 1.7.0
   */
  public void setReturnSnapshot(
      com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot) {
    this.returnSnapshot = snapshot;
  }

  /**
   * Returns the return snapshot attached to this invocation, if any.
   *
   * <p>This is populated by the framework after the method returns. It may be null if the method
   * threw an exception or if the interceptor chain did not reach the return-value recording path.
   *
   * @return the return snapshot, or null
   * @since 1.7.0
   */
  public com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot getReturnSnapshot() {
    return returnSnapshot;
  }
}
