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
 * Specialized inlined advice for interceptors that need to <em>rewrite</em> the
 * first argument of the intercepted method (see
 * {@link com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode#ARGUMENT_REWRITE}).
 *
 * <p>The default {@link InterceptAdvice} binds {@code @Advice.AllArguments}, but
 * ByteBuddy does not write element-level mutations of that array back to the
 * method's parameter slots — so {@code invocation.setArgument(0, ...)} would
 * silently have no effect on the actual call. This advice instead binds the
 * first argument directly with {@code @Advice.Argument(value = 0, readOnly = false)},
 * which <em>does</em> write an assigned value back to the parameter slot.</p>
 *
 * <p>Flow: on method entry, build a short-lived {@link MethodInvocation} whose
 * argument array is {@code [task]}, run every matching interceptor's
 * {@link Interceptor#before(MethodInvocation)} (an interceptor replaces
 * {@code argument[0]} with a context-propagating wrapper), then assign the
 * (possibly rewritten) argument back to the {@code @Advice.Argument} parameter
 * so the method body receives the wrapped task.</p>
 *
 * <p>This advice only handles {@code before}; there is no {@code @Advice.OnMethodExit}
 * because argument-rewrite hooks (currently only async context propagation) have
 * no after/onException work to do.</p>
 */
public class AsyncArgumentAdvice {

    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Class<?> targetClass,
            @Advice.Origin Method method,
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object argument) {
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

            // Build a transient invocation carrying just the single task argument.
            // We do NOT use the MethodInvocationPool here: the pool reuses a single
            // thread-local instance, and we must hold onto this invocation across
            // the before() callbacks to read back any argument rewrite. Allocating
            // a fresh object is acceptable — argument-rewrite hooks fire only on
            // task submission, which is orders of magnitude rarer than general
            // method calls.
            MethodInvocation invocation = new MethodInvocation(
                    targetClass, methodName, method, null, new Object[]{argument});

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

            // Read back the (possibly rewritten) first argument and assign it to
            // the @Advice.Argument parameter. With readOnly=false, ByteBuddy
            // stores this value into the method's first parameter slot before
            // the method body executes — so execute()/submit() receive the
            // context-wrapped task.
            argument = invocation.getArgument(0);
        } catch (Throwable e) {
            // Never let any error escape the advice
        }
    }
}
