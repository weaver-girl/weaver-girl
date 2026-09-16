package com.github.cc11001100.weavergirl.core.introduction;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the mapping from target instances to their introduction delegates.
 *
 * <p>When a class is introduced with an interface, the framework needs to associate each target
 * instance with its delegate. This store provides that mapping.
 *
 * <p>Entries are retained for the lifetime of the target object. In practice this is acceptable for
 * most application objects whose lifetimes are bounded by the request/thread scope.
 *
 * @since 2.0.0
 */
public class IntroductionStore {

  private static final ConcurrentHashMap<Object, Object> STORE = new ConcurrentHashMap<>();

  private IntroductionStore() {}

  /**
   * Bind a delegate to a target instance.
   *
   * @param target the target instance
   * @param delegate the delegate instance
   */
  public static void bind(Object target, Object delegate) {
    if (target != null && delegate != null) {
      STORE.put(target, delegate);
    }
  }

  /**
   * Get the delegate bound to the given target instance.
   *
   * @param target the target instance
   * @return the delegate, or null if none is bound
   */
  public static Object getDelegate(Object target) {
    if (target == null) {
      return null;
    }
    return STORE.get(target);
  }

  /**
   * Remove the delegate binding for the given target instance.
   *
   * @param target the target instance
   */
  public static void unbind(Object target) {
    if (target != null) {
      STORE.remove(target);
    }
  }

  /**
   * Remove all delegate bindings.
   */
  public static void clear() {
    STORE.clear();
  }
}
