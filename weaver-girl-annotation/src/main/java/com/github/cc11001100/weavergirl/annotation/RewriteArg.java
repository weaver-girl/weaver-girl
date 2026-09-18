package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an argument-rewriting advice for a specific target method. The annotated method
 * runs before the target method body and may replace any argument via {@code
 * MethodInvocation#setArgument(int, Object)}; the replacement propagates to the actual call.
 *
 * <p>This is the annotation counterpart of {@link
 * com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode#ARGUMENT_REWRITE}.
 * Unlike {@link Before}, the definition is woven with a specialized advice that binds writable
 * parameter slots, because ByteBuddy does not write element mutations of {@code @Advice.AllArguments}
 * back to the parameter slots.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}. The annotated method must
 * accept a single {@code MethodInvocation} parameter. Only {@code before} semantics apply: the
 * method is invoked on method entry, and there is no after/onException callback for this mode.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.Greeter")
 * public class UppercaseArg {
 *     &#64;RewriteArg("greet")
 *     public void upper(MethodInvocation inv) {
 *         inv.setArgument(0, ((String) inv.getArgument(0)).toUpperCase());
 *     }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @see Before
 * @since 2.1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RewriteArg {

  /**
   * Name of the target method whose arguments may be rewritten.
   *
   * @return the target method name
   */
  String value();

  /**
   * Fully-qualified parameter type names to disambiguate overloaded methods. When empty (default),
   * matches the method by name only.
   *
   * @return parameter type names, or empty array to match by name only
   */
  String[] parameterTypes() default {};
}
