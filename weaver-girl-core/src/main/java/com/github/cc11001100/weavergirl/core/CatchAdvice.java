package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.core.interceptor.CatchInvocationPool;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy Advice class that gets inlined into target <strong>catch blocks</strong>. For method
 * interception, see {@link InterceptAdvice}.
 *
 * <p>This advice supports catch-block interception declared via {@link CatchInterceptor}. At runtime,
 * the advice matches registered catch pointcuts, acquires a pooled {@link CatchInvocation}, and
 * invokes the matching catch interceptors.
 *
 * <h3>Usage</h3>
 *
 * <p>This advice is applied by {@link com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer}
 * when it encounters a method with a catch block matching a registered {@link CatchPointcut}.
 *
 * <h3>Limitations</h3>
 *
 * <p>ByteBuddy cannot directly instrument catch blocks in the same way it instruments methods. The
 * current implementation instruments the enclosing method and uses exception-type analysis to
 * determine which catch interceptors to invoke. Full catch-block instrumentation may require
 * additional ByteBuddy configuration or ASM-level transformations.
 */
public class CatchAdvice {

  @Advice.OnMethodEnter
  public static CatchInvocation onCatchEnter(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Method method,
      @Advice.Thrown Throwable caughtException) {
    try {
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return null;
      }
      InterceptorHolder.incrementInterceptorInvocationCount();
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry =
          InterceptorHolder.getRegistry();
      if (registry == null) {
        return null;
      }
      if (!SamplingController.getInstance().shouldSample()) {
        return null;
      }
      if (caughtException == null) {
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
          if (cp.matches(className, methodName, caughtException.getClass())) {
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

      if (!matched) {
        CatchInvocationPool.release(invocation);
        return null;
      }

      return invocation;
    } catch (Throwable e) {
      return null;
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void onCatchExit(@Advice.Enter CatchInvocation invocation) {
    try {
      if (invocation != null) {
        CatchInvocationPool.release(invocation);
      }
    } catch (Throwable e) {
      // Best-effort cleanup
    }
  }
}
