package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores per-instance state for pertarget/perthis advice.
 *
 * <p>This is a simple identity-based map from target object to an opaque per-instance context object.
 * The framework does not prescribe the contents of the context; interceptors may attach arbitrary
 * state via {@link MethodInvocation#getPerInstance()} / {@link MethodInvocation#setPerInstance(Object)}.
 *
 * <p>Entries are retained for the lifetime of the target object. In practice this is acceptable for
 * most application objects whose lifetimes are bounded by the request/thread scope. For long-lived
 * singletons, the per-instance overhead is negligible because there is only one entry.
 *
 * @since 2.0.0
 */
public class PerInstanceStore {

  private static final ConcurrentHashMap<Object, Object> STORE = new ConcurrentHashMap<>();

  private PerInstanceStore() {}

  /**
   * Get or create the per-instance context for the given target object.
   *
   * <p>If no entry exists yet, a new {@link PerInstanceContext} is created and stored.
   *
   * @param target the target instance, may be null for static targets
   * @return the per-instance context, never null
   */
  public static Object getOrCreate(Object target) {
    if (target == null) {
      return null;
    }
    return STORE.computeIfAbsent(target, key -> new PerInstanceContext());
  }

  /**
   * Remove the per-instance context for the given target object.
   *
   * <p>This is primarily useful for cleanup in tests or when an object is known to be discarded.
   *
   * @param target the target instance
   * @since 2.0.0
   */
  public static void remove(Object target) {
    if (target != null) {
      STORE.remove(target);
    }
  }

  /**
   * Remove all per-instance contexts.
   *
   * <p>This is primarily for testing and shutdown cleanup.
   *
   * @since 2.0.0
   */
  public static void clear() {
    STORE.clear();
  }

  /**
   * Simple marker context for per-instance state.
   *
   * <p>Interceptors may cast the object returned by {@link MethodInvocation#getPerInstance()} to this
   * type and attach fields, or they may use a custom class. The framework does not depend on the
   * concrete type.
   */
  public static final class PerInstanceContext {
    private PerInstanceContext() {}
  }
}
