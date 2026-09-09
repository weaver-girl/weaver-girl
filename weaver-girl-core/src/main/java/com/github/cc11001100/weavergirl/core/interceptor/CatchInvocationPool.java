package com.github.cc11001100.weavergirl.core.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;

/**
 * Thread-local pool for CatchInvocation instances. Reduces GC pressure by reusing CatchInvocation
 * objects instead of allocating new ones on every catch-block entry.
 *
 * <p>Thread safety: Each thread has its own pooled instance, so no synchronization is needed.
 */
public final class CatchInvocationPool {

  private static final ThreadLocal<CatchInvocation> POOL = new ThreadLocal<>();

  private CatchInvocationPool() {}

  /**
   * Acquire a CatchInvocation from the pool, or create a new one if the pool is empty.
   *
   * @param targetClass the class containing the catch block
   * @param methodName the method containing the catch block
   * @param caughtException the exception caught by the block
   * @return a CatchInvocation ready for use
   */
  public static CatchInvocation acquire(
      Class<?> targetClass, String methodName, Throwable caughtException) {
    CatchInvocation inv = POOL.get();
    if (inv != null) {
      POOL.remove();
      inv.reset(targetClass, methodName, caughtException);
      return inv;
    }
    return new CatchInvocation(targetClass, methodName, caughtException);
  }

  /**
   * Return a CatchInvocation to the pool for reuse.
   *
   * @param inv the CatchInvocation to return, may be null
   */
  public static void release(CatchInvocation inv) {
    if (inv != null) {
      inv.clear();
      POOL.set(inv);
    }
  }
}
