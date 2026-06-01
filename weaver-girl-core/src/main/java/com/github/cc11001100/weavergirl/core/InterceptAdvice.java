// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;
import java.util.List;

/**
 * ByteBuddy Advice class that gets inlined into target methods.
 * Delegates to InterceptorRegistry for interceptor lookup and invocation.
 */
public class InterceptAdvice {

    @Advice.OnMethodEnter(skipOn = MethodInvocation.class)
    public static MethodInvocation onMethodEnter(
            @Advice.Origin Class<?> targetClass,
            @Advice.Origin Method method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] arguments) {
        try {
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

            // If any interceptor called skipMethod(), return the invocation to trigger ByteBuddy skipOn
            // — the original method body will NOT execute, and onMethodExit will still be called
            if (invocation.isSkipped()) {
                return invocation;
            }
            // Not skipped — return null so the original method body executes normally
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
            @Advice.Thrown Throwable throwable,
            @Advice.Return(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returnValue) {
        if (invocation == null) {
            return;
        }
        try {
            if (throwable != null) {
                invocation.setThrowable(throwable);
            } else {
                invocation.setReturnValue(returnValue);
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
                            interceptor.onException(invocation);
                        } else {
                            interceptor.after(invocation);
                        }
                    } catch (Exception e) {
                        // Swallow interceptor errors
                    }
                }
            }
        } catch (Exception e) {
            // Never crash the target application
        }
    }
}
