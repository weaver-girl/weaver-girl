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
   * @param className the fully-qualified class name
   * @return true if the class exists in the JVM
   */
  public boolean exists(String className) {
    // Fast path: check already-loaded classes
    if (loadedClassNames.contains(className)) {
      return true;
    }

    // Slow path: try to load the class
    try {
      Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      loadedClassNames.add(className);
      return true;
    } catch (ClassNotFoundException e) {
      // Fallback for test environments where the context classloader
      // may not see the target class directly.
    } catch (NoClassDefFoundError e) {
      // The class exists but one of its dependencies doesn't
      log.debug("Class {} found but has missing dependency: {}", className, e.getMessage());
      return true;
    }

    // Second attempt: use this class's classloader as fallback.
    try {
      Class.forName(className, false, TypeExistenceChecker.class.getClassLoader());
      loadedClassNames.add(className);
      return true;
    } catch (ClassNotFoundException e) {
      // Fall through to resource probe
    } catch (NoClassDefFoundError e) {
      loadedClassNames.add(className);
      return true;
    }

    // Last resort: probe classpath by resource path so classes only
    // loadable via a different classloader are still treated as existing.
    String resource = className.replace('.', '/') + ".class";
    if (TypeExistenceChecker.class.getClassLoader().getResource(resource) != null
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
    try {
      Class<?> clazz =
          Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
        if (method.getName().equals(methodName)) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }
}
