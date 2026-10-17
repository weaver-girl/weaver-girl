package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.interceptor.MethodInvocationPool;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import java.lang.reflect.Constructor;
import java.util.List;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy Advice class that gets inlined into the <strong>static initializer</strong> {@code
 * <clinit>} of woven target classes, backing the {@code @OnStaticInit} annotation.
 *
 * <p><strong>Why a separate class?</strong> A type initializer has no reflective {@link
 * java.lang.reflect.Method} — Java has no {@code Method} object for {@code <clinit>} — so an
 * advice that binds {@code @Advice.Origin Method} (like {@link InterceptAdvice}) cannot be inlined
 * into one. ByteBuddy throws {@code IllegalStateException} at {@code .make()} time
 * ("Cannot represent static <clinit>() as the specified constant") and the whole weave is silently
 * dropped. {@link ConstructorAdvice} has the same problem, which is why it binds {@code @Advice.Origin
 * Constructor<?>}. This advice binds only {@code @Advice.Origin Class<?>}, which ByteBuddy can
 * resolve on a type initializer.
 *
 * <p><strong>No skipOn:</strong> Silently skipping a {@code <clinit>} body would leave the class
 * permanently uninitialized (static fields never set, the JVM aborts on first use). An interceptor
 * that calls {@code invocation.skipMethod()} must not be able to suppress class initialization.
 *
 * <p><strong>No onThrowable:</strong> Like constructors, wrapping a {@code <clinit>} body in a
 * try/catch via {@code @Advice.OnMethodExit(onThrowable)} is illegal at the JVM level — a class
 * initializer throws an {@link ExceptionInInitializerError} and is neither skippable nor resumable.
 * Therefore {@code onException} callbacks are not available for static-init hooks; only {@code
 * before} and {@code after} are invoked.
 */
public class StaticInitAdvice {

  @Advice.OnMethodEnter
  public static Object onMethodEnter(@Advice.Origin Class<?> targetClass) {
    try {
      // Global kill-switch
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return null;
      }
      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        return null;
      }

      // Sampling check
      if (!SamplingController.getInstance().shouldSample()) {
        return null;
      }

      String methodName = "<clinit>";
      String className = targetClass.getName();
      // A type initializer has no Executable: no Method, no Constructor. methodName carries the
      // discriminator instead, exactly as ConstructorAdvice does for "<init>".
      MethodInvocation invocation =
          MethodInvocationPool.acquire(targetClass, methodName, (Constructor<?>) null, null, new Object[0]);

      // cflow tracking: push <clinit> onto the stack so nested cflow expressions can see it.
      PointcutExpression.enterCflow(className, methodName);

      List<InterceptorDefinition> defs = InterceptorHolder.getInterceptorsForClassSnapshot(className);
      for (InterceptorDefinition def : defs) {
        if (!def.getPointcut().matchRuntime(className, methodName, null, new Object[0], null, null)) {
          continue;
        }
        if (!InterceptorHolder.shouldInvoke(def.getName())) {
          continue; // circuit breaker is open
        }
        long hookStart = System.nanoTime();
        try {
          def.getInterceptor().before(invocation);
          long hookNanos = System.nanoTime() - hookStart;
          InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
          AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), true, hookNanos);
        } catch (Throwable e) {
          long hookNanos = System.nanoTime() - hookStart;
          InterceptorHolder.logInterceptorError(def.getName(), "before", e);
          InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
          AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false, hookNanos);
        }
      }

      // Always return the invocation so onMethodExit can run the after() callbacks against the
      // same context. <clinit> must never be skipped, so no skip-on semantics here.
      return invocation;
    } catch (Throwable e) {
      // Never let any error escape the advice
      return null;
    }
  }

  @Advice.OnMethodExit
  public static void onMethodExit(
      @Advice.Enter Object enterValue, @Advice.Origin Class<?> targetClass) {
    try {
      // Global kill-switch
      if (!InterceptorHolder.isInterceptionEnabled()) {
        if (enterValue instanceof MethodInvocation) {
          try {
            MethodInvocationPool.release((MethodInvocation) enterValue);
          } catch (Throwable ignored) {
          }
        }
        return;
      }

      String methodName = "<clinit>";
      MethodInvocation context;
      if (enterValue instanceof MethodInvocation) {
        context = (MethodInvocation) enterValue;
      } else {
        context =
            MethodInvocationPool.acquire(targetClass, methodName, (Constructor<?>) null, null, new Object[0]);
      }

      String className = targetClass.getName();
      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        MethodInvocationPool.release(context);
        return;
      }

      List<InterceptorDefinition> defs = InterceptorHolder.getInterceptorsForClassSnapshot(className);
      for (InterceptorDefinition def : defs) {
        if (!def.getPointcut().matchRuntime(className, methodName, null, new Object[0], null, null)) {
          continue;
        }
        if (!InterceptorHolder.shouldInvoke(def.getName())) {
          continue; // circuit breaker is open
        }
        long hookStart = System.nanoTime();
        try {
          def.getInterceptor().after(context);
          long hookNanos = System.nanoTime() - hookStart;
          InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
          AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), true, hookNanos);
        } catch (Throwable e) {
          long hookNanos = System.nanoTime() - hookStart;
          InterceptorHolder.logInterceptorError(def.getName(), "after", e);
          InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
          AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false, hookNanos);
        }
      }

      // cflow tracking: pop <clinit> off the stack (pushed in onMethodEnter)
      PointcutExpression.exitCflow(className, methodName);

      MethodInvocationPool.release(context);
    } catch (Throwable e) {
      // Never let any error crash the target application
      if (enterValue instanceof MethodInvocation) {
        try {
          MethodInvocationPool.release((MethodInvocation) enterValue);
        } catch (Throwable poolError) {
          System.err.println(
              "[weaver-girl] Failed to release MethodInvocation to pool: "
                  + poolError.getMessage());
        }
      }
    }
  }
}