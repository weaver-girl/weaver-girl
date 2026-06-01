// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
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
            @Advice.This Object target,
            @Advice.AllArguments Object[] arguments) {
        try {
            InterceptorHolder.incrementInterceptorInvocationCount();
            InterceptorRegistry registry = InterceptorHolder.getRegistry();
            if (registry == null) {
                return null;
            }

            String className = targetClass.getName();
            String methodName = method.getName();
            MethodInvocation invocation = new MethodInvocation(targetClass, methodName, target, arguments);

            List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
            for (InterceptorDefinition def : defs) {
                if (def.getPointcut().getMethodMatcher().matches(methodName)) {
                    try {
                        def.getInterceptor().before(invocation);
                    } catch (Exception e) {
                        // Swallow interceptor errors to avoid crashing target app
                    }
                }
            }

            // If any interceptor called skipMethod(), return the invocation to trigger skipOn.
            // The original method body will NOT execute, and onMethodExit will receive this
            // invocation via @Advice.Enter.
            if (invocation.isSkipped()) {
                return invocation;
            }
            // Not skipped — return null. onMethodExit will create a fresh MethodInvocation
            // from the @Advice.Enter null value + other available parameters.
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
            // If onMethodEnter returned null (no skip), we need to create a fresh
            // MethodInvocation for the after/onException callbacks.
            // If onMethodEnter returned an invocation (skip triggered), reuse it.
            MethodInvocation context;
            if (invocation != null) {
                context = invocation;
            } else {
                context = new MethodInvocation(targetClass, method.getName(), target, arguments);
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
                    try {
                        Interceptor interceptor = def.getInterceptor();
                        if (throwable != null) {
                            interceptor.onException(context);
                        } else {
                            interceptor.after(context);
                        }
                    } catch (Exception e) {
                        // Swallow interceptor errors
                    }
                }
            }

            // Write back: if an interceptor explicitly set a return value, replace
            // the original/default return value with the override.
            if (context.isReturnOverridden()) {
                returnValue = context.getReturnValue();
            }
        } catch (Exception e) {
            // Never crash the target application
        }
    }
}
