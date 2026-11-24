package com.github.cc11001100.weavergirl.api.taint;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

/**
 * Turns an interceptor hook into a sanitiser: after the hooked method returns, the marks on its
 * return value (and optionally on one argument) are cleared, because the method has proved the
 * value cannot carry an injection payload.
 *
 * <p>A plugin registers it like any other interceptor, so a sanitiser needs no bespoke advice:
 *
 * <pre>
 * registry.register(new InterceptorDefinition(
 *     "sanitize-html",
 *     new Pointcut(ClassMatcher.byName("org.owasp.encoder.Encode"), MethodMatcher.byName("forHtml")),
 *     TaintSanitizerInterceptor.returnValue()));
 * </pre>
 *
 * <p>The clearer runs on the calling thread, which is the thread whose marks matter: a value that
 * was captured into another thread before the sanitiser ran keeps the copy it was given, and values
 * derived from the sanitised result afterwards are clean.
 *
 * @see Taint#untaint(Object)
 * @see Taint#untaintAll(Object...)
 * @since 1.0.0
 */
public final class TaintSanitizerInterceptor implements Interceptor {

  private final boolean clearReturnValue;
  private final int argumentIndex;

  private TaintSanitizerInterceptor(boolean clearReturnValue, int argumentIndex) {
    this.clearReturnValue = clearReturnValue;
    this.argumentIndex = argumentIndex;
  }

  /**
   * Sanitiser for a transform that returns a new, safe value ({@code escapeHtml}, an encoder).
   *
   * @return an interceptor that clears the return value
   */
  public static TaintSanitizerInterceptor returnValue() {
    return new TaintSanitizerInterceptor(true, -1);
  }

  /**
   * Sanitiser for a validator that cleans its argument in place and returns something else (or
   * nothing).
   *
   * @param argumentIndex zero-based index of the argument to clear
   * @return an interceptor that clears the argument
   */
  public static TaintSanitizerInterceptor argument(int argumentIndex) {
    return new TaintSanitizerInterceptor(false, argumentIndex);
  }

  /**
   * Sanitiser for a method that both returns a safe value and fixes up its argument.
   *
   * @param argumentIndex zero-based index of the argument to clear
   * @return an interceptor that clears the return value and the argument
   */
  public static TaintSanitizerInterceptor returnValueAndArgument(int argumentIndex) {
    return new TaintSanitizerInterceptor(true, argumentIndex);
  }

  @Override
  public void after(MethodInvocation invocation) {
    if (invocation == null) {
      return;
    }
    if (clearReturnValue) {
      Taint.untaint(invocation.getReturnValue());
    }
    if (argumentIndex >= 0) {
      Object[] arguments = invocation.getArguments();
      if (arguments != null && argumentIndex < arguments.length) {
        Taint.untaint(arguments[argumentIndex]);
      }
    }
  }
}
