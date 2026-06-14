package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an interceptor for target classes matching the specified criteria.
 *
 * <p>Multiple matching strategies are supported (evaluated in priority order):
 *
 * <ol>
 *   <li>{@link #pointcut()} — PointcutExpression DSL (highest priority, ignores all other fields)
 *   <li>{@link #targetAnnotation()} — match classes bearing a specific annotation
 *   <li>{@link #targetSuperClass()} — match classes extending a specific superclass
 *   <li>{@link #targetInterface()} — match classes implementing a specific interface
 *   <li>{@link #targetPattern()} — match classes by regex name pattern
 *   <li>{@link #target()} — match a single class by exact name (lowest priority)
 * </ol>
 *
 * <h3>Examples:</h3>
 *
 * <pre>
 * // Exact class name
 * &#64;WeaveClass(target = "com.example.UserService")
 *
 * // Regex pattern
 * &#64;WeaveClass(targetPattern = "com\.example\..*Service")
 *
 * // By annotation
 * &#64;WeaveClass(targetAnnotation = "com.example.Monitored")
 *
 * // By superclass
 * &#64;WeaveClass(targetSuperClass = "com.example.BaseService")
 *
 * // By interface
 * &#64;WeaveClass(targetInterface = "java.io.Serializable")
 *
 * // Full PointcutExpression DSL
 * &#64;WeaveClass(pointcut = "execution(* com.example..*(..)) && @annotation(com.example.Traced)")
 * </pre>
 *
 * @see Before
 * @see After
 * @see Around
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WeaveClass {

  /**
   * Exact fully-qualified class name to intercept. Ignored when {@link #pointcut()}, {@link
   * #targetAnnotation()}, {@link #targetSuperClass()}, {@link #targetInterface()}, or {@link
   * #targetPattern()} is set.
   */
  String target() default "";

  /**
   * Regex pattern for class names to intercept. Ignored when {@link #pointcut()}, {@link
   * #targetAnnotation()}, {@link #targetSuperClass()}, or {@link #targetInterface()} is set.
   */
  String targetPattern() default "";

  /**
   * Fully-qualified annotation class name. Match all classes annotated with this annotation.
   * Ignored when {@link #pointcut()}, {@link #targetSuperClass()}, or {@link #targetInterface()} is
   * set.
   */
  String targetAnnotation() default "";

  /**
   * Fully-qualified superclass name. Match all classes that extend this superclass. Ignored when
   * {@link #pointcut()} or {@link #targetInterface()} is set.
   */
  String targetSuperClass() default "";

  /**
   * Fully-qualified interface name. Match all classes that implement this interface. Ignored when
   * {@link #pointcut()} is set.
   */
  String targetInterface() default "";

  /**
   * PointcutExpression DSL — highest priority, ignores all other fields when set.
   *
   * <p>Supported forms include {@code execution(* com.example..*(..))}, {@code
   * @within(com.example.Monitored)}, {@code subclassOf(com.example.BaseService)}, and {@code
   * execution(* com.example..*(..)) && @annotation(com.example.Traced)}.
   */
  String pointcut() default "";
}
