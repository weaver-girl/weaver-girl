package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.interceptor.CatchInvocationPool;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy Advice class that gets inlined into target <strong>methods</strong> to dispatch to
 * {@link CatchInterceptor} instances when the method exits with an exception.
 *
 * <p>ByteBuddy cannot directly instrument catch blocks in the same way it instruments methods, so
 * this advice instruments the enclosing method and dispatches at method exit with an in-flight
 * exception. A catch interceptor therefore fires whenever a matched method throws a matching
 * exception <em>out</em> of the method body (including rethrows); see {@link
 * com.github.cc11001100.weavergirl.annotation.OnCatch} for the same documented limitation.
 *
 * <p>The dispatch happens in {@link #onCatchExit} on {@code @Advice.OnMethodExit(onThrowable =
 * Throwable.class)}: {@code @Advice.Thrown} is never meaningful at method entry, it is only bound
 * with an exception on the exceptional exit. On a normal return the throwable binding is {@code
 * null} and the advice returns immediately.
 *
 * <h3>Write-back</h3>
 *
 * <p>After the interceptors run, the requested disposition is written back to the exit frame:
 *
 * <ul>
 *   <li>{@link CatchInvocation#setCaughtException(Throwable)} &mdash; the method now throws the
 *       replacement exception instead of the original.
 *   <li>{@link CatchInvocation#setCatchReturnValue(Object)} &mdash; the exception is swallowed and
 *       the method returns the override (non-void methods only; a void method simply returns
 *       normally, as a void method has no value to return).
 *   <li>{@link CatchInvocation#suppressCatch()} &mdash; the exception is swallowed and the method
 *       returns normally with its default value.
 * </ul>
 *
 * <p>When no catch interceptor matched, the original exception keeps propagating untouched.
 */
public class CatchAdvice {

  /**
   * Resolved disposition of an exceptional method exit: whether the thrown exception is replaced,
   * swallowed, or left to propagate, and (when swallowed) the value the method returns instead.
   *
   * <p>Only the first-write-wins controls of {@link CatchInvocation} produce a disposition
   * (replacement, return override, or suppression); multiple interceptors on the same join point
   * therefore resolve to a single, deterministic outcome.
   */
  public static final class CatchDisposition {
    /**
     * The exception the method should throw instead of the original, or null to propagate as-is.
     *
     * <p>Public (not package-private) because this class is referenced from the woven target class,
     * which lives in the application's package, not {@code com.github.cc11001100.weavergirl.core}.
     * Package-private fields would fail with {@code IllegalAccessError} when the inlined advice
     * reads them across the package boundary.
     */
    public final Throwable replacementException;
    /** True when {@link CatchInvocation#setCatchReturnValue} was used. */
    public final boolean returnOverridden;
    /** The value the method should return instead of throwing. */
    public final Object returnValue;
    /** True when {@link CatchInvocation#suppressCatch} was used. */
    public final boolean suppressed;

    CatchDisposition(Throwable replacementException, boolean returnOverridden, Object returnValue, boolean suppressed) {
      this.replacementException = replacementException;
      this.returnOverridden = returnOverridden;
      this.returnValue = returnValue;
      this.suppressed = suppressed;
    }
  }

  @Advice.OnMethodEnter
  public static void onCatchEnter() {
    // Intentionally empty: an enter/exception advice triggers ByteBuddy's exception-aware
    // weaving even for the exceptional exit path. Without this, onThrowable exits that
    // write @Advice.Thrown back have nothing to guard against.
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void onCatchExit(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Method method,
      @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable caughtException,
      @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) {
    try {
      CatchDisposition disposition =
          dispatch(targetClass, method, caughtException, InterceptorHolder.getRegistry());
      if (disposition == null) {
        return; // nothing requested a change: the original exception keeps propagating
      }
      if (disposition.returnOverridden) {
        caughtException = null; // swallow the exception and return the interceptor's value
        returnValue = disposition.returnValue;
      } else if (disposition.replacementException != null) {
        caughtException = disposition.replacementException; // rethrow the replacement
      } else if (disposition.suppressed) {
        caughtException = null; // swallow the exception, method returns its default value
      }
    } catch (Throwable e) {
      // Never let any error (including OOM, StackOverflow) crash the target application
    }
  }

  /**
   * Matches the given caught exception against every registered {@link CatchInterceptor} and invokes
   * its {@code onCatch} callbacks, resolving their accumulated requests into a single disposition.
   *
   * <p>Returns {@code null} when nothing matched (so the exception keeps propagating) or when no
   * interceptor requested a change.
   *
   * <p>Public (not package-private) because this method is invoked from the inlined advice in the
   * woven target class, which lives in the application's package, not {@code
   * com.github.cc11001100.weavergirl.core}; a package-private method would fail with {@code
   * IllegalAccessError} across the package boundary. It is also the direct unit-test entry point.
   */
  public static CatchDisposition dispatch(
      Class<?> targetClass,
      Method method,
      Throwable caughtException,
      InterceptorRegistry registry) {
    try {
      // Method returned normally (or the exit is not exceptional): nothing to catch.
      if (caughtException == null) {
        return null;
      }
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return null;
      }
      InterceptorHolder.incrementInterceptorInvocationCount();
      if (registry == null) {
        return null;
      }
      if (!SamplingController.getInstance().shouldSample()) {
        return null;
      }

      String className = targetClass.getName();
      String methodName = method.getName();
      CatchInvocation invocation =
          CatchInvocationPool.acquire(targetClass, methodName, caughtException);

      boolean matched = false;
      for (InterceptorDefinition def : registry.getAllDefinitions()) {
        if (!(def.getInterceptor() instanceof CatchInterceptor)) {
          continue;
        }
        CatchInterceptor catchInterceptor = (CatchInterceptor) def.getInterceptor();
        for (CatchPointcut cp : catchInterceptor.catchPointcuts()) {
          if (cp.matches(className, methodName, invocation.getCaughtException().getClass())) {
            if (!InterceptorHolder.shouldInvoke(def.getName())) {
              continue;
            }
            matched = true;
            long hookStart = System.nanoTime();
            try {
              catchInterceptor.onCatch(invocation);
              long hookNanos = System.nanoTime() - hookStart;
              InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
              com.github.cc11001100.weavergirl.core.status.AgentStatus.getInstance()
                  .recordInterceptorInvocation(def.getName(), true, hookNanos);
            } catch (Throwable e) {
              long hookNanos = System.nanoTime() - hookStart;
              InterceptorHolder.logInterceptorError(def.getName(), "catch", e);
              InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
              com.github.cc11001100.weavergirl.core.status.AgentStatus.getInstance()
                  .recordInterceptorInvocation(def.getName(), false, hookNanos);
            }
          }
        }
      }

      try {
        if (!matched) {
          return null; // nothing matched: the original exception keeps propagating
        }
        // Resolve the disposition requested by the interceptors; setCaughtException takes
        // precedence over the other two controls (it rethrows, so there is no return path).
        CatchDisposition result;
        if (invocation.isExceptionOverridden()) {
          result =
              new CatchDisposition(invocation.getReplacementException(), false, null, false);
        } else if (invocation.isReturnOverridden()) {
          result =
              new CatchDisposition(null, true, invocation.getCatchReturnValue(), false);
        } else if (invocation.isCatchSuppressed()) {
          result = new CatchDisposition(null, false, null, true);
        } else {
          result = null;
        }
        return result;
      } finally {
        CatchInvocationPool.release(invocation);
      }
    } catch (Throwable e) {
      // Never let any error (including OOM, StackOverflow) crash the target application
      return null;
    }
  }
}
