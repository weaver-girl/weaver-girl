package com.github.cc11001100.weavergirl.core.transformer;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks whether target classes and methods exist in the JVM before attempting to transform them.
 * This prevents NoClassDefFoundError when an interceptor targets a class that is not on the
 * application's classpath.
 *
 * <p>Checks are performed against all loaded classes (via Instrumentation) and by attempting
 * Class.forName() with the target class's ClassLoader.
 */
public class TypeExistenceChecker {

  private static final Logger log = LoggerFactory.getLogger(TypeExistenceChecker.class);

  private final Instrumentation instrumentation;
  private final Set<String> loadedClassNames;

  public TypeExistenceChecker(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
    this.loadedClassNames = new HashSet<>();
    for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
      loadedClassNames.add(clazz.getName());
    }
  }

  /**
   * Check if a class exists and is loadable.
   *
   * <p>This must not load the class. It runs while the transformer is being installed, before {@code
   * installOn} has registered it, so a {@code Class.forName} here would define the target with its
   * original bytes and the interceptor registered for it would never be woven in. Presence is decided
   * from classes already loaded and from the class file being visible as a resource.
   *
   * @param className the fully-qualified class name
   * @return true if the class exists in the JVM
   */
  public boolean exists(String className) {
    // Fast path: check already-loaded classes
    if (loadedClassNames.contains(className)) {
      return true;
    }

    // Probe the classpath by resource. This sees classes the application will load later without
    // defining them now, which is what keeps them transformable.
    String resource = className.replace('.', '/') + ".class";
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    if ((context != null && context.getResource(resource) != null)
        || TypeExistenceChecker.class.getClassLoader().getResource(resource) != null
        || ClassLoader.getSystemClassLoader().getResource(resource) != null) {
      loadedClassNames.add(className);
      return true;
    }
    return false;
  }

  /**
   * Check if a method exists on a class.
   *
   * @param className the fully-qualified class name
   * @param methodName the method name
   * @return true if the class exists and has at least one method with the given name
   */
  public boolean methodExists(String className, String methodName) {
    if (!exists(className)) {
      return false;
    }
    // Only reflect over a class that is already loaded. Loading it here would define it before the
    // transformer is registered, so it could never be woven.
    if (!loadedClassNames.contains(className)) {
      return false;
    }
    for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
      if (className.equals(clazz.getName())) {
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
          if (method.getName().equals(methodName)) {
            return true;
          }
        }
        return false;
      }
    }
    return false;
  }
}
