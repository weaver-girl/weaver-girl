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
 * <p><strong>Return value write-back mechanism:</strong></p>
 * <p>When an interceptor calls {@code invocation.setReturnValue(newValue)}, the
 * modified value must be written back to the actual return variable so the caller
 * receives it. ByteBuddy supports this via {@code @Advice.Return(readOnly = false)},
 * which maps the parameter to a writable local variable slot. After the exit advice
 * completes, ByteBuddy loads from that slot and returns it to the caller.</p>
 *
 * <p><strong>How it works internally:</strong></p>
 * <ul>
 *   <li>{@code readOnly = true} (default): The parameter maps to the return-value slot
 *       as read-only. Writing to it is impossible; the caller always gets the original value.</li>
 *   <li>{@code readOnly = false}: The parameter maps to the return-value slot with both
 *       a read assignment and a write assignment. Any store to this parameter in the advice
 *       method body writes back through the write assignment, replacing the return value.</li>
 *   <li>{@code typing = DYNAMIC}: Allows the advice parameter type ({@code Object}) to be
 *       cast to the instrumented method's actual return type at runtime, even for primitives
 *       (which are auto-boxed/unboxed).</li>
 * </ul>
 *
 * <p><strong>skipOn interaction:</strong> When {@code skipOn} triggers and the original
 * method body is skipped, ByteBuddy stores a default value (null/0/false) into the
 * return-value slot, then runs the exit advice. With {@code readOnly = false}, the exit
 * advice can write the interceptor's override value into that slot, replacing the default.</p>
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

    /**
     * Exit advice that runs after the target method returns or throws.
     *
     * <p>The {@code @Advice.Return(readOnly = false)} parameter allows writing back
     * a modified return value. When an interceptor calls
     * {@code invocation.setReturnValue(newValue)}, we assign that value to the
     * {@code returnValue} parameter here. ByteBuddy then stores it into the
     * return-value local variable slot, replacing whatever the original method
     * returned (or the default value if the method was skipped).</p>
     *
     * <p>{@code typing = DYNAMIC} is required because the advice parameter type is
     * {@code Object} but the instrumented method may return a primitive or a narrower
     * reference type. Dynamic typing handles the runtime cast/unbox.</p>
     *
     * <p><strong>Void method constraint:</strong> For void methods, ByteBuddy maps
     * {@code @Advice.Return} to a default-value target (no return slot exists). Writing
     * back is meaningless for void methods — the method returns nothing regardless.
     * The {@code isReturnOverridden()} check ensures we don't attempt a nonsensical
     * write-back on void methods.</p>
     *
     * <p><strong>Primitive method constraint:</strong> For methods returning primitives,
     * the interceptor's {@code setReturnValue} must supply a boxed value of the correct
     * type (e.g., {@code Integer} for an {@code int}-returning method). Dynamic typing
     * auto-unboxes. Supplying {@code null} or a wrong type will cause a
     * {@code ClassCastException} at runtime.</p>
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Enter MethodInvocation invocation,
            @Advice.Origin Class<?> targetClass,
            @Advice.Origin Method method,
            @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable throwable,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) {
        if (invocation == null) {
            return;
        }
        try {
            // Store the original return value / throwable into the invocation context.
            // Use initReturnValue (package-private) rather than setReturnValue so that
            // the isReturnOverridden flag is NOT set. This allows us to distinguish
            // "framework stored the original" from "interceptor explicitly overrode it".
            if (throwable != null) {
                invocation.setThrowable(throwable);
            } else {
                invocation.initReturnValue(returnValue);
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

            // Write back: if an interceptor explicitly set a return value, replace
            // the original/default return value with the override.
            //
            // When the interceptor calls invocation.setReturnValue(newValue), the
            // isReturnOverridden flag is set to true. We check that flag here and,
            // if set, assign the override value to the returnValue parameter.
            //
            // ByteBuddy with readOnly=false generates bytecode that stores this
            // assignment back into the return-value local variable slot. After
            // onMethodExit returns, ByteBuddy loads from that slot and returns it
            // to the caller.
            //
            // For reference-return types: Object -> Object assignment is always valid.
            // For primitive-return types: Dynamic typing auto-unboxes. The interceptor
            //   must supply the correct boxed type (Integer for int, etc).
            // For void methods: returnValue parameter maps to a default-value target;
            //   isReturnOverridden will be true but the write-back has no effect.
            if (invocation.isReturnOverridden()) {
                returnValue = invocation.getReturnValue();
            }

            // Throwable write-back: if an interceptor suppressed the exception
            // (set throwable to null via a future setThrowable override API),
            // write null back to clear it. Currently not fully implemented.
        } catch (Exception e) {
            // Never crash the target application
        }
    }
}
