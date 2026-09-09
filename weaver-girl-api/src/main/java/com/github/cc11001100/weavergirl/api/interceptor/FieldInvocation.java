package com.github.cc11001100.weavergirl.api.interceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * Context object passed to {@link FieldInterceptor} callbacks at runtime, encapsulating all
 * information about an intercepted field access.
 *
 * <h3>Field read override</h3>
 *
 * <p>Call {@link #setReturnValue(Object)} from {@link FieldInterceptor#onFieldGet(FieldInvocation,
 * Object)} to replace the value read from the field. If multiple interceptors call this method, the
 * last call wins. Within a single callback, only the first call has an effect; subsequent calls are
 * ignored.
 *
 * <h3>Field write override / rejection</h3>
 *
 * <p>Call {@link #setWriteValue(Object)} from {@link FieldInterceptor#onFieldSet(FieldInvocation,
 * Object, Object)} to modify the value before it is stored to the field. Call {@link
 * #skipWrite()} to reject the write entirely; when skipped, the field is not modified and the
 * original value remains.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Field access callbacks are executed sequentially on the same thread that accessed the field.
 * Multiple field accesses may execute concurrently across threads, but each individual {@code
 * FieldInvocation} is confined to one thread. Therefore, no synchronization is needed when
 * accessing or mutating a {@code FieldInvocation} within a field-interceptor callback.
 *
 * @since 1.7.0
 */
public class FieldInvocation {

  private Class<?> targetClass;
  private String fieldName;
  private String fieldTypeName;
  private Object target;
  private boolean isStatic;
  private Object returnValue;
  private Object writeValue;
  private boolean returnOverridden;
  private boolean writeOverridden;
  private boolean writeSkipped;
  private Map<String, Object> attachments;
  private com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot returnSnapshot;

  /**
   * Constructs a new FieldInvocation for a field read.
   *
   * @param targetClass the class declaring the field
   * @param fieldName the field name
   * @param fieldTypeName the field type name, or null if unknown
   * @param target the object instance on which the field is accessed (null for static fields)
   */
  public FieldInvocation(
      Class<?> targetClass, String fieldName, String fieldTypeName, Object target) {
    this.targetClass = targetClass;
    this.fieldName = fieldName;
    this.fieldTypeName = fieldTypeName;
    this.target = target;
    this.isStatic = target == null;
  }

  /**
   * Reset this instance for reuse from an object pool. This is an internal framework method;
   * interceptors should not call this.
   *
   * @param targetClass the class declaring the field
   * @param fieldName the field name
   * @param fieldTypeName the field type name, or null if unknown
   * @param target the object instance on which the field is accessed, or null for static fields
   */
  public void reset(Class<?> targetClass, String fieldName, String fieldTypeName, Object target) {
    this.targetClass = targetClass;
    this.fieldName = fieldName;
    this.fieldTypeName = fieldTypeName;
    this.target = target;
    this.isStatic = target == null;
    this.returnValue = null;
    this.writeValue = null;
    this.returnOverridden = false;
    this.writeOverridden = false;
    this.writeSkipped = false;
    this.returnSnapshot = null;
    if (this.attachments != null) {
      this.attachments.clear();
    }
  }

  /**
   * Clear all object references to prevent memory leaks when pooled.
   */
  public void clear() {
    this.targetClass = null;
    this.target = null;
    this.fieldName = null;
    this.fieldTypeName = null;
    this.returnValue = null;
    this.writeValue = null;
    this.returnOverridden = false;
    this.writeOverridden = false;
    this.writeSkipped = false;
    this.returnSnapshot = null;
    if (this.attachments != null) {
      this.attachments.clear();
    }
  }

  // --- Attachment API (state passing between field interceptors) ---

  /**
   * Stores an attachment value identified by the given key.
   *
   * @param key the attachment key
   * @param value the attachment value (may be null)
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

  /**
   * Returns whether an attachment with the given key exists.
   *
   * @param key the attachment key
   * @return true if an attachment with this key exists
   */
  public boolean hasAttachment(String key) {
    return this.attachments != null && this.attachments.containsKey(key);
  }

  // --- Field metadata ---

  /**
   * Returns the class that declares the intercepted field.
   *
   * @return the target class, never null
   */
  public Class<?> getTargetClass() {
    return targetClass;
  }

  /**
   * Returns the name of the intercepted field.
   *
   * @return the field name, never null
   */
  public String getFieldName() {
    return fieldName;
  }

  /**
   * Returns the type name of the intercepted field (fully-qualified class name), if known.
   *
   * @return the field type name, or null if unknown
   */
  public String getFieldTypeName() {
    return fieldTypeName;
  }

  /**
   * Returns the object instance on which the field is accessed.
   *
   * @return the target instance, or null for static fields
   */
  public Object getTarget() {
    return target;
  }

  /**
   * Returns whether the field is static.
   *
   * @return true if the field is static
   */
  public boolean isStatic() {
    return isStatic;
  }

  // --- Read override API ---

  /**
   * Returns the override value for a field read, if set by an interceptor.
   *
   * @return the override value, or null if not set
   */
  public Object getReturnValue() {
    return returnValue;
  }

  /**
   * Overrides the value returned by a field read. Call this from {@link
   * FieldInterceptor#onFieldGet(FieldInvocation, Object)} to replace the original field value.
   *
   * <p>Only the first call has an effect; subsequent calls are ignored. If multiple interceptors
   * call this method, the last call wins.
   *
   * @param returnValue the value to return instead of the original field value
   */
  public void setReturnValue(Object returnValue) {
    if (!returnOverridden) {
      this.returnValue = returnValue;
      this.returnOverridden = true;
    }
  }

  /**
   * Returns whether an interceptor has explicitly set a return value via {@link #setReturnValue(Object)}.
   *
   * @return true if the return value was set by an interceptor
   */
  public boolean isReturnOverridden() {
    return returnOverridden;
  }

  // --- Write override API ---

  /**
   * Returns the override value for a field write, if set by an interceptor.
   *
   * @return the override value, or null if not set
   */
  public Object getWriteValue() {
    return writeValue;
  }

  /**
   * Overrides the value being written to a field. Call this from {@link
   * FieldInterceptor#onFieldSet(FieldInvocation, Object, Object)} to modify the value before it is
   * stored.
   *
   * <p>Only the first call has an effect; subsequent calls are ignored. If multiple interceptors
   * call this method, the last call wins.
   *
   * @param writeValue the value to write instead of the original value
   */
  public void setWriteValue(Object writeValue) {
    if (!writeOverridden) {
      this.writeValue = writeValue;
      this.writeOverridden = true;
    }
  }

  /**
   * Returns whether an interceptor has explicitly set a write value via {@link #setWriteValue(Object)}.
   *
   * @return true if the write value was set by an interceptor
   */
  public boolean isWriteOverridden() {
    return writeOverridden;
  }

  /**
   * Prevents the field write from occurring. When called from {@link
   * FieldInterceptor#onFieldSet(FieldInvocation, Object, Object)}, the field is not modified and
   * the original value remains.
   */
  public void skipWrite() {
    this.writeSkipped = true;
  }

  /**
   * Returns whether the field write has been skipped via {@link #skipWrite()}.
   *
   * @return true if the write should not occur
   */
  public boolean isWriteSkipped() {
    return writeSkipped;
  }

  // --- Return snapshot API ---

  /**
   * Stores a return-value snapshot for downstream consumers (tracing, caching).
   *
   * @param snapshot the snapshot to store, or {@code null} to clear
   */
  public void setReturnSnapshot(com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot) {
    this.returnSnapshot = snapshot;
  }

  /**
   * Returns the stored return-value snapshot, if any.
   *
   * @return the snapshot, or {@code null} if not set
   */
  public com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot getReturnSnapshot() {
    return returnSnapshot;
  }
}
