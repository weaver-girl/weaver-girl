package com.github.cc11001100.weavergirl.core.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;

/**
 * Thread-local pool for FieldInvocation instances. Reduces GC pressure by reusing FieldInvocation
 * objects instead of allocating new ones on every field access.
 *
 * <p>Thread safety: Each thread has its own pooled instance, so no synchronization is needed.
 */
public final class FieldInvocationPool {

  private static final ThreadLocal<FieldInvocation> POOL = new ThreadLocal<>();

  private FieldInvocationPool() {}

  /**
   * Acquire a FieldInvocation from the pool, or create a new one if the pool is empty.
   *
   * @param targetClass the class declaring the field
   * @param fieldName the field name
   * @param fieldTypeName the field type name, or null if unknown
   * @param target the object instance on which the field is accessed, or null for static fields
   * @return a FieldInvocation ready for use
   */
  public static FieldInvocation acquire(
      Class<?> targetClass, String fieldName, String fieldTypeName, Object target) {
    FieldInvocation inv = POOL.get();
    if (inv != null) {
      POOL.remove();
      inv.reset(targetClass, fieldName, fieldTypeName, target);
      return inv;
    }
    return new FieldInvocation(targetClass, fieldName, fieldTypeName, target);
  }

  /**
   * Return a FieldInvocation to the pool for reuse.
   *
   * @param inv the FieldInvocation to return, may be null
   */
  public static void release(FieldInvocation inv) {
    if (inv != null) {
      inv.clear();
      POOL.set(inv);
    }
  }
}
