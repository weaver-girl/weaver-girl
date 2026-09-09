package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.*;

/**
 * Marks an interceptor method to be invoked when a specific exception type is caught inside
 * intercepted methods.
 *
 * <p>This annotation is applied to methods within a class that implements {@link
 * com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor}. The annotated methods are
 * automatically discovered by the framework and registered as catch-block interceptors.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * public class MyCatchInterceptor implements CatchInterceptor {
 *     &#64;OnCatch("java.io.IOException")
 *     public void onIoException(CatchInvocation invocation) {
 *         // Handle IOException
 *         invocation.suppressCatch();
 *         invocation.setCatchReturnValue(defaultValue);
 *     }
 * }</pre>
 *
 * <h3>Limitations</h3>
 *
 * <p>ByteBuddy cannot directly instrument catch blocks in the same way it instruments methods. The
 * current implementation instruments the <em>enclosing method</em> and uses exception-type analysis
 * to determine which catch interceptors to invoke. This means:
 *
 * <ul>
 *   <li>Catch interceptors are invoked for any matching exception thrown from the method body,
 *       constructor initializer, or super constructor call.
 *   <li>The specific catch block where the exception would have been caught cannot be distinguished
 *       if multiple catch blocks handle the same exception type or subtypes.
 * </ul>
 *
 * <p>For precise catch-block interception, use method-level interception with {@link
 * com.github.cc11001100.weavergirl.api.interceptor.Interceptor} and check the exception type in
 * {@link com.github.cc11001100.weavergirl.api.interceptor.Interceptor#onException(MethodInvocation)}.
 *
 * @see com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor
 * @see com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation
 * @see com.github.cc11001100.weavergirl.api.interceptor.CatchPointcut
 * @since 1.7.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnCatch {

  /**
   * The fully-qualified class name of the exception type to intercept.
   *
   * <p>This can be a concrete exception class (e.g., {@code "java.io.IOException"}) or a base class
   * (e.g., {@code "java.lang.Exception"}). Subtypes are also matched.
   *
   * @return the exception type name
   */
  String value();
}
