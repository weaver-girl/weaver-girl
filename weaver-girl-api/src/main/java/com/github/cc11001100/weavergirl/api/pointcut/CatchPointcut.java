package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import java.util.Objects;

/**
 * Selects join points by class, method, <em>and</em> caught exception type criteria.
 *
 * <p>A {@code CatchPointcut} is used by the core engine when it instruments exception catch blocks.
 * The core engine evaluates the exception type criteria in addition to the class/method criteria
 * when deciding whether to apply catch-block interception.
 *
 * <h3>Usage</h3>
 *
 * <p>In most cases, plugin developers will not need to construct {@code CatchPointcut} directly.
 * When you do need one, the common patterns are:
 *
 * <pre>
 * // Intercept catch blocks handling a specific exception type
 * CatchPointcut p1 = CatchPointcut.catching(Exception.class);
 *
 * // Intercept catch blocks in a specific class/method
 * CatchPointcut p2 = CatchPointcut.inMethod(
 *     new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
 *     IOException.class);</pre>
 *
 * @see CatchInterceptor
 * @since 1.8.0
 */
public class CatchPointcut {

  private final com.github.cc11001100.weavergirl.api.pointcut.Pointcut methodPointcut;
  private final String exceptionTypeName;

  /**
   * Creates a new catch pointcut.
   *
   * @param methodPointcut selects the enclosing method
   * @param exceptionTypeName the fully-qualified name of the exception type to catch
   */
  public CatchPointcut(
      com.github.cc11001100.weavergirl.api.pointcut.Pointcut methodPointcut,
      String exceptionTypeName) {
    this.methodPointcut = methodPointcut;
    this.exceptionTypeName = exceptionTypeName;
  }

  /**
   * Creates a catch pointcut that applies to all methods within a matched class.
   *
   * @param classMatcher selects the target classes
   * @param exceptionType the exception type to catch
   * @return a new CatchPointcut
   */
  public static CatchPointcut inClass(
      com.github.cc11001100.weavergirl.api.matcher.ClassMatcher classMatcher,
      Class<? extends Throwable> exceptionType) {
    return new CatchPointcut(
        new com.github.cc11001100.weavergirl.api.pointcut.Pointcut(classMatcher, MethodMatcher.any()),
        exceptionType.getName());
  }

  /**
   * Creates a catch pointcut that applies to a specific method within a matched class.
   *
   * @param methodPointcut selects the enclosing method
   * @param exceptionType the exception type to catch
   * @return a new CatchPointcut
   */
  public static CatchPointcut inMethod(
      com.github.cc11001100.weavergirl.api.pointcut.Pointcut methodPointcut,
      Class<? extends Throwable> exceptionType) {
    return new CatchPointcut(methodPointcut, exceptionType.getName());
  }

  /**
   * Returns the method pointcut component.
   *
   * @return the method pointcut, never null
   */
  public com.github.cc11001100.weavergirl.api.pointcut.Pointcut getMethodPointcut() {
    return methodPointcut;
  }

  /**
   * Returns the fully-qualified name of the exception type to catch.
   *
   * @return the exception type name, never null
   */
  public String getExceptionTypeName() {
    return exceptionTypeName;
  }

  /**
   * Tests whether the given class, method, and caught exception match this pointcut.
   *
   * @param className the fully-qualified class name to test
   * @param methodName the method name to test
   * @param caughtExceptionType the class of the caught exception, or null if unknown
   * @return true if all criteria match
   */
  public boolean matches(String className, String methodName, Class<?> caughtExceptionType) {
    if (!methodPointcut.matches(className, methodName)) {
      return false;
    }
    if (exceptionTypeName == null) {
      return true;
    }
    if (caughtExceptionType == null) {
      return false;
    }
    for (Class<?> c = caughtExceptionType; c != null; c = c.getSuperclass()) {
      if (exceptionTypeName.equals(c.getName())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CatchPointcut)) return false;
    CatchPointcut that = (CatchPointcut) o;
    return Objects.equals(methodPointcut, that.methodPointcut)
        && Objects.equals(exceptionTypeName, that.exceptionTypeName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(methodPointcut, exceptionTypeName);
  }

  @Override
  public String toString() {
    return "CatchPointcut{" + methodPointcut + ", exception=" + exceptionTypeName + "}";
  }
}
