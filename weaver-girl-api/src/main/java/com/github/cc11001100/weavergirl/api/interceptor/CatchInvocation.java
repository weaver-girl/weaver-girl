package com.github.cc11001100.weavergirl.api.interceptor;

import java.util.Map;

/**
 * Callback context for catch-block interception, passed to {@link CatchInterceptor} callbacks.
 *
 * <p>Unlike {@link MethodInvocation}, which represents a full method invocation lifecycle, a {@code
 * CatchInvocation} represents a single exception catch block. It provides access to the caught
 * exception, the catch block's enclosing method, and controls for suppressing or modifying the
 * exception handling behavior.
 *
 * <h3>Exception suppression / replacement</h3>
 *
 * <p>Call {@link #suppressCatch()} to prevent the catch block from executing. When suppressed, the
 * original exception continues to propagate as if the catch block did not exist. Call {@link
 * #setCatchReturnValue(Object)} to replace the return value of the catch block (if the catch block
 * returns a value). Call {@link #setCaughtException(Throwable)} to replace the caught exception with
 * a different one.
 *
 * <h3>Conflict resolution</h3>
 *
 * <p>When multiple interceptors interact with the same catch block, the last write wins. Because
 * interceptors execute in priority order, the highest-priority interceptor that runs last determines
 * the final behavior.
 *
 * @since 1.8.0
 */
public class CatchInvocation {

  private Class<?> targetClass;
  private String methodName;
  private Throwable caughtException;
  private Throwable replacementException;
  private Object catchReturnValue;
  private boolean catchSuppressed;
  private boolean returnOverridden;
  private boolean exceptionOverridden;
  private boolean exceptionSuppressed;
  private Map<String, Object> attachments;

  /**
   * Constructs a new CatchInvocation.
   *
   * @param targetClass the class containing the catch block
   * @param methodName the method containing the catch block
   * @param caughtException the exception caught by the block
   */
  public CatchInvocation(Class<?> targetClass, String methodName, Throwable caughtException) {
    this.targetClass = targetClass;
    this.methodName = methodName;
    this.caughtException = caughtException;
  }

  /**
   * Reset this instance for reuse from an object pool.
   *
   * @param targetClass the class containing the catch block
   * @param methodName the method containing the catch block
   * @param caughtException the exception caught by the block
   */
  public void reset(Class<?> targetClass, String methodName, Throwable caughtException) {
    this.targetClass = targetClass;
    this.methodName = methodName;
    this.caughtException = caughtException;
    this.replacementException = null;
    this.catchReturnValue = null;
    this.catchSuppressed = false;
    this.returnOverridden = false;
    this.exceptionOverridden = false;
    this.exceptionSuppressed = false;
    if (this.attachments != null) {
      this.attachments.clear();
    }
  }

  /**
   * Clear all object references to prevent memory leaks when pooled.
   */
  public void clear() {
    this.targetClass = null;
    this.methodName = null;
    this.caughtException = null;
    this.replacementException = null;
    this.catchReturnValue = null;
    this.catchSuppressed = false;
    this.returnOverridden = false;
    this.exceptionOverridden = false;
    this.exceptionSuppressed = false;
    if (this.attachments != null) {
      this.attachments.clear();
    }
  }

  // --- Attachment API ---

  /**
   * Stores an attachment value identified by the given key.
   *
   * @param key the attachment key
   * @param value the attachment value (may be null)
   */
  public void setAttachment(String key, Object value) {
    if (this.attachments == null) {
      this.attachments = new java.util.HashMap<>();
    }
    this.attachments.put(key, value);
  }

  /**
   * Retrieves an attachment value by key.
   *
   * @param key the attachment key
   * @return the attachment value, or {@code null} if not found
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
   */
  public Object removeAttachment(String key) {
    return this.attachments != null ? this.attachments.remove(key) : null;
  }

  // --- Catch metadata ---

  /**
   * Returns the class containing the catch block.
   *
   * @return the target class, never null
   */
  public Class<?> getTargetClass() {
    return targetClass;
  }

  /**
   * Returns the name of the method containing the catch block.
   *
   * @return the method name, never null
   */
  public String getMethodName() {
    return methodName;
  }

  /**
   * Returns the exception caught by the catch block.
   *
   * @return the caught exception, never null
   */
  public Throwable getCaughtException() {
    return caughtException;
  }

  // --- Catch behavior controls ---

  /**
   * Prevents the catch block from executing. When called from {@link
   * CatchInterceptor#onCatch(CatchInvocation)}, the catch block body is skipped and the original
   * exception continues to propagate as if the catch block did not exist.
   */
  public void suppressCatch() {
    this.catchSuppressed = true;
  }

  /**
   * Returns whether the catch block has been suppressed via {@link #suppressCatch()}.
   *
   * @return true if the catch block should not execute
   */
  public boolean isCatchSuppressed() {
    return catchSuppressed;
  }

  /**
   * Replaces the caught exception with a different exception. Call this from {@link
   * CatchInterceptor#onCatch(CatchInvocation)} to substitute the exception that the catch block
   * handles.
   *
   * <p>Only the first call has an effect; subsequent calls are ignored.
   *
   * @param exception the replacement exception
   */
  public void setCaughtException(Throwable exception) {
    if (!exceptionOverridden && exception != null) {
      this.replacementException = exception;
      this.exceptionOverridden = true;
    }
  }

  /**
   * Returns the replacement exception set by an interceptor, or null if not set.
   *
   * @return the replacement exception, or null
   */
  public Throwable getReplacementException() {
    return replacementException;
  }

  /**
   * Returns whether an interceptor has explicitly set a replacement exception via {@link
   * #setCaughtException(Throwable)}.
   *
   * @return true if the caught exception was replaced
   */
  public boolean isExceptionOverridden() {
    return exceptionOverridden;
  }

  /**
   * Sets the return value of the catch block. Call this from {@link
   * CatchInterceptor#onCatch(CatchInvocation)} to override the value returned by the catch block.
   *
   * <p>Only the first call has an effect; subsequent calls are ignored.
   *
   * @param returnValue the value to return from the catch block
   */
  public void setCatchReturnValue(Object returnValue) {
    if (!returnOverridden) {
      this.catchReturnValue = returnValue;
      this.returnOverridden = true;
    }
  }

  /**
   * Returns the override return value for the catch block, or null if not set.
   *
   * @return the override return value, or null
   */
  public Object getCatchReturnValue() {
    return catchReturnValue;
  }

  /**
   * Returns whether an interceptor has explicitly set a catch return value via {@link
   * #setCatchReturnValue(Object)}.
   *
   * @return true if the catch return value was overridden
   */
  public boolean isReturnOverridden() {
    return returnOverridden;
  }
}
