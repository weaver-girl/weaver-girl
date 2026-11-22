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

  /** Delegates bound by target class name; see {@link #bindClassName}. */
  private static final ConcurrentHashMap<String, Object> BY_NAME = new ConcurrentHashMap<>();

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
   * The delegate for {@code target}: the one bound to that exact instance, else the one bound to its
   * class.
   *
   * <p>An introduction registered for a type is not created per instance — the application makes
   * its own objects and the plugin never sees them. A class-level delegate is what lets one
   * definition serve every instance of the type without the application asking for it.
   *
   * @param target the target instance
   * @return the delegate, or null when neither the instance nor its class has one
   */
  public static Object resolveDelegate(Object target) {
    if (target == null) {
      return null;
    }
    Object bound = STORE.get(target);
    if (bound != null) {
      return bound;
    }
    return STORE.get(target.getClass());
  }

  /**
   * Bind a delegate to every instance of {@code targetType}, present and future.
   *
   * @param targetType the introduced type
   * @param delegate the delegate instance
   * @since 1.0.0
   */
  public static void bindType(Class<?> targetType, Object delegate) {
    if (targetType != null && delegate != null) {
      STORE.put(targetType, delegate);
    }
  }

  /**
   * Bind a delegate to every instance of a type named by {@code className}.
   *
   * <p>Resolved at call time rather than bind time: the delegate is needed while the target class
   * is still being defined, and looking the class up then would load a second copy of it. By the
   * time an introduced method runs the class exists, so the name resolves to the right one.
   *
   * @param className the introduced type's name
   * @param delegate the delegate instance
   * @since 1.0.0
   */
  public static void bindClassName(String className, Object delegate) {
    if (className != null && delegate != null) {
      BY_NAME.put(className, delegate);
    }
  }

  /**
   * Remove a name binding, so a plugin that is unloaded no longer answers for the type.
   *
   * @param className the introduced type's name
   * @since 1.0.0
   */
  public static void unbindClassName(String className) {
    if (className != null) {
      BY_NAME.remove(className);
    }
  }

  /**
   * The delegate for an instance, also consulting the name bindings.
   *
   * @param target the target instance
   * @return the delegate, or null
   */
  public static Object resolveWithName(Object target) {
    Object delegate = resolveDelegate(target);
    if (delegate != null) {
      return delegate;
    }
    return BY_NAME.get(target.getClass().getName());
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
