package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Specialized inlined advice for interceptors that need to <em>rewrite</em>
 * any argument of the intercepted method (see
 * {@link com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode#ARGUMENT_REWRITE}).
 *
 * <p>The default {@link InterceptAdvice} binds {@code @Advice.AllArguments}, but
 * ByteBuddy does not write element-level mutations of that array back to the
 * method's parameter slots — so {@code invocation.setArgument(i, ...)} would
 * silently have no effect on the actual call. This advice instead binds each
 * of the first {@value #MAX_REWRITABLE_ARGUMENTS} arguments directly with
 * {@code @Advice.Argument(value = i, readOnly = false, optional = true)},
 * which <em>does</em> write an assigned value back to the parameter slot.
 * Indices beyond the method's actual arity are bound as absent optionals:
 * writes to them are silently ignored by ByteBuddy (verified by
 * {@code OptionalArgumentSpikeTest}), so a single advice class covers methods
 * of any arity up to the bound.</p>
 *
 * <p>Flow: on method entry, build a short-lived {@link MethodInvocation} whose
 * argument array is a copy of the full {@code @Advice.AllArguments}, run every
 * matching interceptor's {@link Interceptor#before(MethodInvocation)} (an
 * interceptor replaces any {@code argument[i]} with a context-propagating
 * wrapper), then assign each (possibly rewritten) argument back to its
 * {@code @Advice.Argument} parameter so the method body receives the wrapped
 * values.</p>
 *
 * <p>This advice only handles {@code before}; there is no {@code @Advice.OnMethodExit}
 * because argument-rewrite hooks (currently only async context propagation) have
 * no after/onException work to do.</p>
 */
public class AsyncArgumentAdvice {

    /**
     * Number of leading parameter slots bound writably. Covers every current
     * async hook (max arity 4: {@code scheduleAtFixedRate}) with headroom for
     * future multi-argument rewrites such as executor-as-second-argument
     * factory methods.
     */
    static final int MAX_REWRITABLE_ARGUMENTS = 8;

    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Class<?> targetClass,
            @Advice.Origin Method method,
            @Advice.AllArguments Object[] allArguments,
            @Advice.Argument(value = 0, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg0,
            @Advice.Argument(value = 1, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg1,
            @Advice.Argument(value = 2, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg2,
            @Advice.Argument(value = 3, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg3,
            @Advice.Argument(value = 4, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg4,
            @Advice.Argument(value = 5, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg5,
            @Advice.Argument(value = 6, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg6,
            @Advice.Argument(value = 7, readOnly = false, optional = true,
                    typing = Assigner.Typing.DYNAMIC) Object arg7) {
        try {
            if (!InterceptorHolder.isInterceptionEnabled()) {
                return;
            }
            InterceptorHolder.incrementInterceptorInvocationCount();
            InterceptorRegistry registry = InterceptorHolder.getRegistry();
            if (registry == null) {
                return;
            }

            String methodName = method.getName();
            String className = targetClass.getName();
            Object[] snapshot = allArguments != null ? allArguments.clone() : new Object[0];

            // Build a transient invocation carrying the full argument list.
            // We do NOT use the MethodInvocationPool here: the pool reuses a single
            // thread-local instance, and we must hold onto this invocation across
            // the before() callbacks to read back any argument rewrite. Allocating
            // a fresh object is acceptable — argument-rewrite hooks fire only on
            // task submission, which is orders of magnitude rarer than general
            // method calls.
            MethodInvocation invocation = new MethodInvocation(
                    targetClass, methodName, method, null, snapshot);

            List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
            for (InterceptorDefinition def : defs) {
                if (def.getAdviceMode()
                        != com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE) {
                    continue;
                }
                if (!def.getPointcut().getMethodMatcher().matches(methodName, method.getParameterTypes())) {
                    continue;
                }
                if (!InterceptorHolder.shouldInvoke(def.getName())) {
                    continue;
                }
                long hookStart = System.nanoTime();
                try {
                    def.getInterceptor().before(invocation);
                    long hookNanos = System.nanoTime() - hookStart;
                    InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
                    AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), true, hookNanos);
                } catch (Throwable e) {
                    long hookNanos = System.nanoTime() - hookStart;
                    invocation.setSkipMethod(false);
                    InterceptorHolder.logInterceptorError(def.getName(), "before", e);
                    InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
                    AgentStatus.getInstance().recordInterceptorInvocation(def.getName(), false, hookNanos);
                }
            }

            // Read back the (possibly rewritten) arguments and assign them to
            // the @Advice.Argument parameters. With readOnly=false, ByteBuddy
            // stores each value into the method's corresponding parameter slot
            // before the method body executes. Indices at or beyond the actual
            // arity are skipped: the invocation array only holds real arguments,
            // and writes to absent optional slots would be ignored anyway.
            int argCount = invocation.getArguments().length;
            if (argCount > 0) {
                arg0 = invocation.getArgument(0);
            }
            if (argCount > 1) {
                arg1 = invocation.getArgument(1);
            }
            if (argCount > 2) {
                arg2 = invocation.getArgument(2);
            }
            if (argCount > 3) {
                arg3 = invocation.getArgument(3);
            }
            if (argCount > 4) {
                arg4 = invocation.getArgument(4);
            }
            if (argCount > 5) {
                arg5 = invocation.getArgument(5);
            }
            if (argCount > 6) {
                arg6 = invocation.getArgument(6);
            }
            if (argCount > 7) {
                arg7 = invocation.getArgument(7);
            }
        } catch (Throwable e) {
            // Never let any error escape the advice
        }
    }
}
