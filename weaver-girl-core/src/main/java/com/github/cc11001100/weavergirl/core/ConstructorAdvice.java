package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.interceptor.MethodInvocationPool;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import java.lang.reflect.Constructor;
import java.util.List;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy Advice class that gets inlined into target <strong>constructors</strong>. For method
 * interception, see {@link InterceptAdvice}.
 *
 * <p><strong>Why a separate class?</strong> ByteBuddy cannot bind both {@code @Advice.Origin
 * Method} and {@code @Advice.Origin Constructor<?>} in the same advice — a method joinpoint has no
 * Constructor and vice versa. Using both in one class causes the advice to fail to apply at
 * runtime.
 *
 * <p><strong>No skipOn:</strong> The JVM does not allow skipping a constructor body (that would
 * produce an uninitialized object). Even if an interceptor calls {@code invocation.skipMethod()},
 * the constructor body still executes.
 *
 * <p><strong>No onThrowable:</strong> ByteBuddy's {@code @Advice.OnMethodExit(onThrowable)} wraps
 * the constructor body in a try-catch, which is illegal in the JVM for constructors (the super()
 * call cannot be inside a try block). Therefore, {@code onException} callbacks are not available
 * for constructor hooks — only {@code before} and {@code after} are invoked.
 *
 * <p><strong>Return value:</strong> For constructors, {@code @Advice.Return} is read-only in the
 * JVM spec, so we don't bind it. The constructed object is available via {@code @Advice.This} in
 * the exit advice.
 */
public class ConstructorAdvice {

  @Advice.OnMethodEnter
  public static MethodInvocation onMethodEnter(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Constructor<?> constructor,
      @Advice.This(optional = true) Object target,
      @Advice.AllArguments Object[] arguments) {
    try {
      // Global kill-switch
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return null;
      }
      InterceptorHolder.incrementInterceptorInvocationCount();
      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        return null;
      }

      // Sampling check
      if (!SamplingController.getInstance().shouldSample()) {
        return null;
      }

      String methodName = "<init>";
      String className = targetClass.getName();
      MethodInvocation invocation =
          MethodInvocationPool.acquire(targetClass, methodName, constructor, target, arguments);

      // Capture caller information from the call stack.
      // Delegated to InterceptorHolder to keep the advice class free of
      // stack-walking code (ByteBuddy validates the entire advice class).
      InterceptorHolder.captureCaller(invocation);

      List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
      for (InterceptorDefinition def : defs) {
        // Use the two-arg matches() to also check parameter types for
        // CONSTRUCTOR matchers that specify a signature (e.g.
        // byConstructor("java.lang.String,int")).
        if (def.getPointcut()
            .getMethodMatcher()
            .matches(methodName, constructor.getParameterTypes())) {
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
      }

      // Constructors CANNOT be skipped — force-clear any skipMethod() call.
      // The JVM requires the constructor body to execute to produce a valid object.
      if (invocation.isSkipped()) {
        invocation.setSkipMethod(false);
      }

      // Return the invocation to onMethodExit via @Advice.Enter.
      // onMethodExit will update the target from @Advice.This (which is
      // the constructed object at exit time, unlike enter where it's null).
      return invocation;
    } catch (Throwable e) {
      // Never let any error escape the advice
      return null;
    }
  }

  @Advice.OnMethodExit
  public static void onMethodExit(
      @Advice.Enter MethodInvocation invocation,
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Constructor<?> constructor,
      @Advice.This(optional = true) Object target,
      @Advice.AllArguments Object[] arguments) {
    try {
      // Global kill-switch
      if (!InterceptorHolder.isInterceptionEnabled()) {
        if (invocation != null) {
          try {
            MethodInvocationPool.release(invocation);
          } catch (Throwable ignored) {
          }
        }
        return;
      }

      String methodName = "<init>";

      MethodInvocation context;
      if (invocation != null) {
        context = invocation;
        // In onMethodEnter, @Advice.This is null (object not yet constructed).
        // Here in onMethodExit, @Advice.This is the fully-constructed object.
        // Update the invocation's target so interceptors can access the object.
        if (target != null && context.getTarget() == null) {
          context.setTarget(target);
        }
      } else {
        context =
            MethodInvocationPool.acquire(targetClass, methodName, constructor, target, arguments);
      }

      String className = targetClass.getName();

      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        MethodInvocationPool.release(context);
        return;
      }

      List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
      for (InterceptorDefinition def : defs) {
        // Use the two-arg matches() to also check parameter types for
        // CONSTRUCTOR matchers that specify a signature.
        if (def.getPointcut()
            .getMethodMatcher()
            .matches(methodName, constructor.getParameterTypes())) {
          if (!InterceptorHolder.shouldInvoke(def.getName())) {
            continue; // circuit breaker is open
          }
          long hookStart = System.nanoTime();
          try {
            Interceptor interceptor = def.getInterceptor();
            interceptor.after(context);
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
      }

      MethodInvocationPool.release(context);
    } catch (Throwable e) {
      // Never let any error crash the target application
      if (invocation != null) {
        try {
          MethodInvocationPool.release(invocation);
        } catch (Throwable poolError) {
          System.err.println(
              "[weaver-girl] Failed to release MethodInvocation to pool: "
                  + poolError.getMessage());
        }
      }
    }
  }
}
