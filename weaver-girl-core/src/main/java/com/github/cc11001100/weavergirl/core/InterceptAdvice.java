package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.context.MdcInjector;
import com.github.cc11001100.weavergirl.core.interceptor.MethodInvocationPool;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy Advice class that gets inlined into target <strong>methods</strong> (regular and
 * static). For constructor interception, see {@link ConstructorAdvice}.
 *
 * <p>Delegates to InterceptorRegistry for interceptor lookup and invocation.
 *
 * <p><strong>skipOn mechanism:</strong> ByteBuddy's {@code skipOn = MethodInvocation.class} means:
 * if onMethodEnter returns a non-null MethodInvocation, the original method body is SKIPPED. If it
 * returns null, the original method executes normally.
 *
 * <p><strong>Return value write-back:</strong> {@code @Advice.Return(readOnly = false)} allows the
 * exit advice to write a modified return value back to the caller. When an interceptor calls {@code
 * invocation.setReturnValue(newValue)}, we assign it to the returnValue parameter, and ByteBuddy
 * stores it into the return-value local variable slot.
 *
 * <p><strong>Automatic MDC injection:</strong> When SLF4J is available, this advice automatically
 * injects trace/span/tenant context from ThreadContext into MDC before method execution and
 * restores the previous MDC state after method completion. This ensures application log statements
 * are automatically correlated with the current trace context without manual MDC management.
 */
public class InterceptAdvice {

  public static final ThreadLocal<MdcInjector.MdcSnapshot> MDC_SNAPSHOT =
      new ThreadLocal<>();

  @Advice.OnMethodEnter(skipOn = MethodInvocation.class)
  public static MethodInvocation onMethodEnter(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Method method,
      @Advice.This(optional = true) Object target,
      @Advice.AllArguments Object[] arguments) {
    try {
      // Global kill-switch: one volatile read; when disabled, incur zero dispatch cost.
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return null;
      }
      InterceptorHolder.incrementInterceptorInvocationCount();
      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        return null;
      }

      // Sampling check: skip interception if not sampled this invocation
      if (!SamplingController.getInstance().shouldSample()) {
        return null;
      }

      // Automatic MDC injection: capture previous MDC state and inject trace/span/tenant
      // context from ThreadContext so application logs are automatically correlated.
      if (MdcInjector.isMdcAvailable()) {
        MDC_SNAPSHOT.set(MdcInjector.inject());
      }

      String methodName = method.getName();
      String className = targetClass.getName();
      MethodInvocation invocation =
          MethodInvocationPool.acquire(targetClass, methodName, method, target, arguments);

      // Capture caller information from the call stack.
      // Delegated to InterceptorHolder to keep the advice class free of
      // stack-walking code (ByteBuddy validates the entire advice class
      // during inlining and rejects methods that reference getStackTrace).
      InterceptorHolder.captureCaller(invocation);

      // cflow tracking: push current method onto the cflow stack before evaluating
      // runtime conditions, so nested cflow expressions can see this method in their
      // caller chain. Pop on exit.
      PointcutExpression.enterCflow(className, methodName);

      List<InterceptorDefinition> defs = InterceptorHolder.getInterceptorsForClassSnapshot(className);
      List<InterceptorDefinition> aroundDefs = new ArrayList<>();
      for (InterceptorDefinition def : defs) {
        if (def.getPointcut().getMethodMatcher().matches(methodName)) {
          if (!InterceptorHolder.shouldInvoke(def.getName())) {
            continue; // circuit breaker is open
          }
          // Evaluate runtime conditions (cflow/if)
          PointcutExpression expr = def.getPointcut().getExpression();
          if (expr != null
              && (expr.getType() == PointcutExpression.Type.CFLOW
                  || expr.getType() == PointcutExpression.Type.IF)) {
            if (!expr.evaluateRuntimeCondition(
                className, methodName, arguments, null, null)) {
              continue; // condition not met, skip this interceptor
            }
          }
          if (def.getInterceptor().hasAround()) {
            aroundDefs.add(def);
          } else {
            // Invoke @Before / non-around callbacks before any @Around advice.
            long hookStart = System.nanoTime();
            try {
              def.getInterceptor().before(invocation);
              long hookNanos = System.nanoTime() - hookStart;
              InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
              AgentStatus.getInstance()
                  .recordInterceptorInvocation(def.getName(), true, hookNanos);
            } catch (Throwable e) {
              long hookNanos = System.nanoTime() - hookStart;
              InterceptorHolder.logInterceptorError(def.getName(), "before", e);
              InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
              AgentStatus.getInstance()
                  .recordInterceptorInvocation(def.getName(), false, hookNanos);
            }
          }
        }

        if (def.getPointcut().isInstanceBound() && !invocation.hasPerInstance()) {
          Object perInstance = PerInstanceStore.getOrCreate(target);
          invocation.setPerInstance(perInstance);
        }
      }

      // Execute around-advice callbacks in priority order. Each around interceptor
      // may call proceed() to continue, or skip the method entirely. If any
      // around interceptor skips, the original method body does not execute.
      boolean proceedAllowed = true;
      for (InterceptorDefinition def : aroundDefs) {
        long hookStart = System.nanoTime();
        try {
          // Mark invocation as proceedable before invoking around advice
          invocation.setProceedable(true);
          def.getInterceptor().around(invocation);
          if (!invocation.isProceedCalled()) {
            proceedAllowed = false;
            invocation.setSkipMethod(true);
          }
          long hookNanos = System.nanoTime() - hookStart;
          InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
          AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), true, hookNanos);
        } catch (Throwable e) {
          long hookNanos = System.nanoTime() - hookStart;
          InterceptorHolder.logInterceptorError(def.getName(), "around", e);
          InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
          AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false, hookNanos);
          proceedAllowed = false;
          invocation.setSkipMethod(true);
        }
      }

      // If any around interceptor did not call proceed(), prevent the method from executing.
      // This distinguishes "interceptor skipped" from "interceptor ran but forgot to call proceed"
      // and avoids the previous isProceedable() check which conflated depth exhaustion with skipping.
      if (invocation.isSkipped() || !proceedAllowed) {
        return invocation;
      }

      // If any interceptor called skipMethod(), return the invocation to trigger skipOn.
      // The original method body will NOT execute, and onMethodExit will receive this
      // invocation via @Advice.Enter and will release it back to the pool.
      if (invocation.isSkipped()) {
        return invocation;
      }
      // Not skipped — release back to pool and return null. onMethodExit will acquire
      // a fresh MethodInvocation from the pool using the available parameters.
      MethodInvocationPool.release(invocation);
      return null;
    } catch (Throwable e) {
      // Never let any error (including OOM, StackOverflow) escape the advice
      return null;
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void onMethodExit(
      @Advice.Enter MethodInvocation invocation,
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Method method,
      @Advice.This(optional = true) Object target,
      @Advice.AllArguments Object[] arguments,
      @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable throwable,
      @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) {
    try {
      // Global kill-switch: when disabled, skip all after/onException callbacks.
      // Release any pooled invocation that enter may have returned (skip case).
      if (!InterceptorHolder.isInterceptionEnabled()) {
        if (invocation != null) {
          try {
            MethodInvocationPool.release(invocation);
          } catch (Throwable ignored) {
          }
        }
        return;
      }

      String methodName = method.getName();

      // If onMethodEnter returned null (no skip), acquire a fresh MethodInvocation
      // from the pool for the after/onException callbacks.
      // If onMethodEnter returned an invocation (skip triggered), reuse it.
      MethodInvocation context;
      if (invocation != null) {
        context = invocation;
      } else {
        context = MethodInvocationPool.acquire(targetClass, methodName, method, target, arguments);
      }

      // Store the original return value / throwable into the invocation context.
      if (throwable != null) {
        InterceptorHolder.incrementInterceptorErrorCount();
        context.setThrowable(throwable);
      } else {
        context.initReturnValue(returnValue);
      }

      String className = targetClass.getName();

      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        MethodInvocationPool.release(context);
        return;
      }

      List<InterceptorDefinition> defs = InterceptorHolder.getInterceptorsForClassSnapshot(className);
      for (InterceptorDefinition def : defs) {
        if (def.getPointcut().getMethodMatcher().matches(methodName)) {
          if (!InterceptorHolder.shouldInvoke(def.getName())) {
            continue; // circuit breaker is open
          }
          PointcutExpression expr = def.getPointcut().getExpression();
          if (expr != null
              && (expr.getType() == PointcutExpression.Type.CFLOW
                  || expr.getType() == PointcutExpression.Type.IF)) {
            boolean matched =
                expr.evaluateRuntimeCondition(
                    className, methodName, arguments, returnValue, throwable);
            if (!matched) {
              continue;
            }
          }
          long hookStart = System.nanoTime();
          try {
            Interceptor interceptor = def.getInterceptor();
            if (throwable != null) {
              interceptor.onException(context);
            } else {
              interceptor.after(context);
            }
            try {
              interceptor.afterFinally(context);
            } catch (Throwable e) {
              long hookNanos = System.nanoTime() - hookStart;
              InterceptorHolder.logInterceptorError(def.getName(), "afterFinally", e);
              InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
              AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false, hookNanos);
            }
            long hookNanos = System.nanoTime() - hookStart;
            InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
            AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), true, hookNanos);
          } catch (Throwable e) {
            // Catch Throwable to prevent OOM/StackOverflow from plugins
            // crashing the target application
            long hookNanos = System.nanoTime() - hookStart;
            InterceptorHolder.logInterceptorError(
                def.getName(), throwable != null ? "onException" : "after", e);
            InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
            AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false, hookNanos);
          }
        }
      }

      // Handle exception suppression: if an interceptor called suppressException()
      // and there was a throwable, clear it so it doesn't propagate.
      if (context.isExceptionSuppressed() && throwable != null) {
        throwable = null; // Clear the throwable so it doesn't propagate
        if (context.isReturnOverridden()) {
          returnValue = context.getReturnValue();
        }
      }

      // Write back: if an interceptor explicitly set a return value, replace
      // the original/default return value with the override.
      if (context.isReturnOverridden()) {
        returnValue = context.getReturnValue();
      }

      // Version the return value for downstream consumers (tracing, caching).
      if (throwable == null) {
        String methodKey = targetClass.getName() + "." + methodName;
        try {
          long version = com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion.next(methodKey);
          com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot =
              new com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot(
                  returnValue, version, methodName, method.getReturnType());
          context.setReturnSnapshot(snapshot);
        } catch (Throwable versionError) {
          // Never let bookkeeping break the return path
        }
      }

      // Restore MDC to the state before this intercepted method executed.
      if (MdcInjector.isMdcAvailable()) {
        MdcInjector.MdcSnapshot snapshot = MDC_SNAPSHOT.get();
        if (snapshot != null) {
          MdcInjector.restore(snapshot);
        }
        MDC_SNAPSHOT.remove();
      }

      // cflow tracking: pop current method from the cflow stack on exit
      PointcutExpression.exitCflow(className, methodName);

      // Return the MethodInvocation to the pool for reuse.
      MethodInvocationPool.release(context);
    } catch (Throwable e) {
      // Never let any error (including OOM, StackOverflow) crash the target application
      // If we have a pooled invocation, release it to prevent pool leak
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
