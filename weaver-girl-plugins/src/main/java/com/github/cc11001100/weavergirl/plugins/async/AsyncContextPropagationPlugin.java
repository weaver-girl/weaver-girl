package com.github.cc11001100.weavergirl.plugins.async;

import com.github.cc11001100.weavergirl.api.context.ContextCallable;
import com.github.cc11001100.weavergirl.api.context.ContextRunnable;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * Asynchronous context propagation plugin.
 *
 * <p>Automatically wraps {@link Runnable} and {@link Callable} arguments passed
 * to {@link java.util.concurrent.Executor} and {@link java.util.concurrent.ExecutorService}
 * methods so that the calling thread's {@link ThreadContext} is captured at
 * submission time and restored in the worker thread before the task runs.</p>
 *
 * <p>This is what makes {@code ThreadContext} (e.g. a traceId set in a servlet
 * interceptor) flow transparently across thread boundaries: application code
 * submits a plain {@code Runnable} to an {@code ExecutorService}, and the worker
 * thread still sees the same traceId — without any manual wrapping by the
 * application.</p>
 *
 * <h3>How it works</h3>
 * <p>The plugin intercepts the submission methods and, in {@code before()},
 * replaces the first argument with a {@link ContextRunnable} or
 * {@link ContextCallable} wrapper if it isn't already wrapped. The wrapper
 * captures {@link ThreadContext#capture()} at construction (i.e. at submission
 * time, on the submitting thread) and restores it inside the worker thread.</p>
 *
 * <h3>Idempotency</h3>
 * <p>To avoid double-wrapping when tasks pass through multiple executors, the
 * interceptor checks whether the argument is already a {@code ContextRunnable}
 * / {@code ContextCallable} and skips wrapping in that case.</p>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 *
 * @see ThreadContext
 * @see ContextRunnable
 * @see ContextCallable
 * @since 1.5.0
 */
public class AsyncContextPropagationPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(AsyncContextPropagationPlugin.class);

    private static final String EXECUTOR_INTERFACE = "java.util.concurrent.Executor";
    private static final String EXECUTOR_SERVICE_INTERFACE = "java.util.concurrent.ExecutorService";

    private boolean enabled = true;

    @Override
    public String name() {
        return "async-context-propagation";
    }

    @Override
    public void init(PluginContext context) {
        enabled = context.getConfigBoolean("enabled", true);
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor wrapInterceptor = new AsyncWrapInterceptor();

        // Executor.execute(Runnable)
        registry.register(buildDefinition("async-execute",
                EXECUTOR_INTERFACE, MethodMatcher.bySignature("execute", "java.lang.Runnable"),
                wrapInterceptor));
        // Also match ExecutorService implementations (they are Executors too)
        registry.register(buildDefinition("async-execute-es",
                EXECUTOR_SERVICE_INTERFACE, MethodMatcher.bySignature("execute", "java.lang.Runnable"),
                wrapInterceptor));

        // ExecutorService.submit(Runnable)
        registry.register(buildDefinition("async-submit-runnable",
                EXECUTOR_SERVICE_INTERFACE, MethodMatcher.bySignature("submit", "java.lang.Runnable"),
                wrapInterceptor));

        // ExecutorService.submit(Callable)
        registry.register(buildDefinition("async-submit-callable",
                EXECUTOR_SERVICE_INTERFACE, MethodMatcher.bySignature("submit", "java.util.concurrent.Callable"),
                wrapInterceptor));

        if (log.isInfoEnabled()) {
            log.info("[async-context-propagation] Registered Executor/ExecutorService hooks for ThreadContext propagation");
        }
    }

    private InterceptorDefinition buildDefinition(String name, String interfaceName,
                                                  MethodMatcher methodMatcher, Interceptor interceptor) {
        // Run early so the wrapped task carries context before any other plugin's
        // after-callbacks observe the submission.
        // ARGUMENT_REWRITE mode: the transformer selects AsyncArgumentAdvice, which
        // binds @Advice.Argument(0, readOnly=false) so the Runnable/Callable we
        // swap in via setArgument(0, ...) actually reaches the executor's method
        // body. (InterceptAdvice's @Advice.AllArguments cannot write element
        // mutations back to parameter slots.)
        return new InterceptorDefinition(name,
                new Pointcut(ClassMatcher.byInterface(interfaceName), methodMatcher),
                interceptor, -100,
                InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE);
    }

    /**
     * Interceptor that wraps the first argument (a Runnable or Callable) in a
     * context-propagating wrapper, unless it is already wrapped.
     */
    static final class AsyncWrapInterceptor implements Interceptor {

        @Override
        public void before(MethodInvocation invocation) {
            if (invocation.getArguments().length == 0) {
                return;
            }
            Object arg = invocation.getArgument(0);
            if (arg instanceof ContextRunnable || arg instanceof ContextCallable) {
                // Already wrapped — avoid double-wrapping when tasks flow through
                // multiple executors.
                return;
            }
            if (arg instanceof Runnable && !(arg instanceof Callable)) {
                // Submitting a Runnable. Note: a Callable is also Runnable in some
                // adapters, so we explicitly exclude Callable here to keep the
                // return value semantics intact.
                invocation.setArgument(0, new ContextRunnable((Runnable) arg));
            } else if (arg instanceof Callable) {
                invocation.setArgument(0, new ContextCallable<>((Callable<?>) arg));
            }
            // Other argument types (e.g. submit(Runnable, result)) are left untouched;
            // the Runnable in those overloads could be wrapped in a future enhancement.
        }
    }
}
