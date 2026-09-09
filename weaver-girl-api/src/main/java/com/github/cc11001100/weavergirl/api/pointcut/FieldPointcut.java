package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import java.util.Objects;

/**
 * Selects join points by class and method <em>and</em> field criteria.
 *
 * <p>A {@code FieldPointcut} is used by the core engine when it instruments field access (getfield /
 * putfield). The core engine evaluates the field criteria in addition to the class/method criteria
 * when deciding whether to apply field interception.
 *
 * <h3>Usage</h3>
 *
 * <p>In most cases, plugin developers will not need to construct {@code FieldPointcut} directly. The
 * higher-level plugin DSL (e.g., {@code AbstractPlugin}) can build one from field matcher
 * expressions. When you do need one, the common patterns are:
 *
 * <pre>
 * // Intercept access to a specific field
 * FieldPointcut p1 = FieldPointcut.byField(FieldMatcher.byName("password"));
 *
 * // Intercept access to any field of a given type
 * FieldPointcut p2 = FieldPointcut.byField(FieldMatcher.byType("java.lang.String"));</pre>
 *
 * @see FieldMatcher
 * @see com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor
 * @since 1.7.0
 */
public class FieldPointcut {

  private final com.github.cc11001100.weavergirl.api.pointcut.Pointcut methodPointcut;
  private final FieldMatcher fieldMatcher;

  /**
   * Creates a new field pointcut.
   *
   * <p>The method pointcut selects the enclosing method context (class + method), and the field
   * matcher selects the target field within that context.
   *
   * @param methodPointcut selects the enclosing method
   * @param fieldMatcher selects the target field
   */
  public FieldPointcut(
      com.github.cc11001100.weavergirl.api.pointcut.Pointcut methodPointcut, FieldMatcher fieldMatcher) {
    this.methodPointcut = methodPointcut;
    this.fieldMatcher = fieldMatcher;
  }

  /**
   * Creates a field pointcut that applies to all methods within a matched class.
   *
   * <p>This is equivalent to {@code new FieldPointcut(new Pointcut(classMatcher,
   * MethodMatcher.any()), fieldMatcher)}.
   *
   * @param classMatcher selects the target classes
   * @param fieldMatcher selects the target field
   * @return a new FieldPointcut
   */
  public static FieldPointcut inClass(
      com.github.cc11001100.weavergirl.api.matcher.ClassMatcher classMatcher, FieldMatcher fieldMatcher) {
    return new FieldPointcut(
        new com.github.cc11001100.weavergirl.api.pointcut.Pointcut(classMatcher, MethodMatcher.any()),
        fieldMatcher);
  }

  /**
   * Creates a field pointcut that applies to a specific field in any class.
   *
   * <p>This is equivalent to {@code FieldPointcut.inClass(ClassMatcher.any(), fieldMatcher)}.
   *
   * @param fieldMatcher selects the target field
   * @return a new FieldPointcut
   */
  public static FieldPointcut byField(FieldMatcher fieldMatcher) {
    return inClass(com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.any(), fieldMatcher);
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
   * Returns the field matcher component.
   *
   * @return the field matcher, never null
   */
  public FieldMatcher getFieldMatcher() {
    return fieldMatcher;
  }

  /**
   * Tests whether the given class, method, and field match this pointcut.
   *
   * @param className the fully-qualified class name to test
   * @param methodName the method name to test
   * @param fieldName the field name to test
   * @param fieldTypeName the field type name, or null if unknown
   * @return true if all criteria match
   */
  public boolean matches(String className, String methodName, String fieldName, String fieldTypeName) {
    return methodPointcut.matches(className, methodName)
        && fieldMatcher.matches(fieldName, fieldTypeName);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FieldPointcut)) return false;
    FieldPointcut that = (FieldPointcut) o;
    return Objects.equals(methodPointcut, that.methodPointcut)
        && Objects.equals(fieldMatcher, that.fieldMatcher);
  }

  @Override
  public int hashCode() {
    return Objects.hash(methodPointcut, fieldMatcher);
  }

  @Override
  public String toString() {
    return "FieldPointcut{" + methodPointcut + ", field=" + fieldMatcher + "}";
  }
}
