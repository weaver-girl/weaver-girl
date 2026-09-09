package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a reusable named pointcut definition within an aspect class.
 *
 * <p>Methods annotated with {@code @Pointcut} are not invoked at runtime; they serve as named
 * containers for pointcut expressions that can be referenced from other advice annotations via
 * {@link #value()}. This mirrors AspectJ's {@code @Pointcut} annotation and enables pointcut reuse
 * across multiple advice methods within the same {@link WeaveClass}.
 *
 * <h3>Usage example:</h3>
 *
 * <pre>
 * &#64;WeaveClass
 * public class MyAspect {
 *
 *   &#64;Pointcut("execution(* com.example..Service.process(..))")
 *   public void serviceProcess() {}
 *
 *   &#64;Before("serviceProcess()")
 *   public void beforeProcess(MethodInvocation inv) { ... }
 *
 *   &#64;After("serviceProcess()")
 *   public void afterProcess(MethodInvocation inv) { ... }
 * }
 * </pre>
 *
 * <p>Pointcut references use the method name followed by parentheses, e.g., {@code
 * "serviceProcess()"}. The framework resolves these references during class scanning and replaces
 * them with the underlying pointcut expression before registration.
 *
 * <h3>Built-in pointcut designators:</h3>
 *
 * <p>The expression syntax supports all standard designators: {@code execution}, {@code call},
 * {@code handler}, {@code @annotation}, {@code @within}, {@code within}, {@code @args},
 * {@code @target}, {@code subclassOf}, {@code implementing}, plus boolean composition operators
 * {@code &&}, {@code ||}, and {@code !}.
 *
 * @see WeaveClass
 * @see Before
 * @see After
 * @see Around
 * @since 2.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pointcut {

  /**
   * The pointcut expression to bind to this named pointcut.
   *
   * <p>This expression follows the same syntax as {@link WeaveClass#pointcut()}. It can reference
   * built-in designators, compose multiple conditions with boolean operators, and may reference
   * other named pointcuts defined in the same aspect class.
   *
   * @return the pointcut expression
   */
  String value();
}
