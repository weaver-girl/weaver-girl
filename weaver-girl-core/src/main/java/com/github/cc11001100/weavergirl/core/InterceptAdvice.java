package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.interceptor.MethodInvocationPool;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;
import java.util.List;

/**
 * ByteBuddy Advice class that gets inlined into target methods.
 * Delegates to InterceptorRegistry for interceptor lookup and invocation.
 *
 * <p><strong>skipOn mechanism:</strong> ByteBuddy's {@code skipOn = MethodInvocation.class}
 * means: if onMethodEnter returns a non-null MethodInvocation, the original method body
 * is SKIPPED. If it returns null, the original method executes normally.</p>
 *
 * <p><strong>Challenge:</strong> When skipOn triggers, onMethodExit receives the MethodInvocation
 * via {@code @Advice.Enter}. But when skipOn does NOT trigger (normal execution), onMethodEnter
 * returns null and onMethodExit has no invocation context. We solve this by having onMethodExit
 * create a fresh MethodInvocation when @Advice.Enter is null.</p>
 *
 * <p><strong>Return value write-back:</strong> {@code @Advice.Return(readOnly = false)} allows
 * the exit advice to write a modified return value back to the caller. When an interceptor
 * calls {@code invocation.setReturnValue(newValue)}, we assign it to the returnValue parameter,
 * and ByteBuddy stores it into the return-value local variable slot.</p>
 */
public class InterceptAdvice {

    @Advice.OnMethodEnter(skipOn = MethodInvocation.class)
    public static MethodInvocation onMethodEnter(
            @Advice.Origin Class<?> targetClass,
            @Advice.Origin Method method,
            @Advice.This(optional = true) Object target,
            @Advice.AllArguments Object[] arguments) {
        try {
            InterceptorHolder.incrementInterceptorInvocationCount();
            InterceptorRegistry registry = InterceptorHolder.getRegistry();
            if (registry == null) {
                return null;
            }

            String className = targetClass.getName();
            String methodName = method.getName();
            MethodInvocation invocation = MethodInvocationPool.acquire(targetClass, methodName, method, target, arguments);

            List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
            for (InterceptorDefinition def : defs) {
                if (def.getPointcut().getMethodMatcher().matches(methodName)) {
                    if (!InterceptorHolder.shouldInvoke(def.getName())) {
                        continue; // circuit breaker is open
                    }
                    try {
                        def.getInterceptor().before(invocation);
                        InterceptorHolder.recordInterceptorSuccess(def.getName());
                        AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), true);
                    } catch (Exception e) {
                        // If this interceptor called skipMethod and then failed,
                        // don't let its skip decision stand
                        invocation.setSkipMethod(false);
                        InterceptorHolder.logInterceptorError(def.getName(), "before", e);
                        InterceptorHolder.recordInterceptorFailure(def.getName());
                        AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false);
                    }
                }
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
        } catch (Exception e) {
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
            // If onMethodEnter returned null (no skip), acquire a fresh MethodInvocation
            // from the pool for the after/onException callbacks.
            // If onMethodEnter returned an invocation (skip triggered), reuse it.
            MethodInvocation context;
            if (invocation != null) {
                context = invocation;
            } else {
                context = MethodInvocationPool.acquire(targetClass, method.getName(), method, target, arguments);
            }

            // Store the original return value / throwable into the invocation context.
            if (throwable != null) {
                InterceptorHolder.incrementInterceptorErrorCount();
                context.setThrowable(throwable);
            } else {
                context.initReturnValue(returnValue);
            }

            String className = targetClass.getName();
            String methodName = method.getName();

            InterceptorRegistry registry = InterceptorHolder.getRegistry();
            if (registry == null) {
                return;
            }

            List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
            for (InterceptorDefinition def : defs) {
                if (def.getPointcut().getMethodMatcher().matches(methodName)) {
                    if (!InterceptorHolder.shouldInvoke(def.getName())) {
                        continue; // circuit breaker is open
                    }
                    try {
                        Interceptor interceptor = def.getInterceptor();
                        if (throwable != null) {
                            interceptor.onException(context);
                        } else {
                            interceptor.after(context);
                        }
                        InterceptorHolder.recordInterceptorSuccess(def.getName());
                        AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), true);
                    } catch (Exception e) {
                        InterceptorHolder.logInterceptorError(def.getName(),
                                throwable != null ? "onException" : "after", e);
                        InterceptorHolder.recordInterceptorFailure(def.getName());
                        AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false);
                    }
                }
            }

            // Handle exception suppression: if an interceptor called suppressException()
            // and there was a throwable, clear it so it doesn't propagate.
            if (context.isExceptionSuppressed() && throwable != null) {
                throwable = null;  // Clear the throwable so it doesn't propagate
                if (context.isReturnOverridden()) {
                    returnValue = context.getReturnValue();
                }
            }

            // Write back: if an interceptor explicitly set a return value, replace
            // the original/default return value with the override.
            if (context.isReturnOverridden()) {
                returnValue = context.getReturnValue();
            }

            // Return the MethodInvocation to the pool for reuse.
            MethodInvocationPool.release(context);
        } catch (Exception e) {
            // Never crash the target application
        }
    }
}
