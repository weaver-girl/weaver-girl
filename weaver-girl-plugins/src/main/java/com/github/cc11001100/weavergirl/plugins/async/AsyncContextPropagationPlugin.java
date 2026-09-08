package com.github.cc11001100.weavergirl.plugins.async;

import com.github.cc11001100.weavergirl.api.context.ContextCallable;
import com.github.cc11001100.weavergirl.api.context.ContextPropagators;
import com.github.cc11001100.weavergirl.api.context.ContextRunnable;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Collection;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous context propagation plugin.
 *
 * <p>Automatically wraps {@link Runnable} and {@link Callable} arguments passed to {@link
 * java.util.concurrent.Executor} and {@link java.util.concurrent.ExecutorService} and {@link
 * java.util.concurrent.ScheduledExecutorService} methods so that the calling thread's propagatable
 * context is captured at submission time and restored in the worker thread before the task runs.
 *
 * <p>This is what makes {@code ThreadContext} (e.g. a traceId set in a servlet interceptor) flow
 * transparently across thread boundaries: application code submits a plain {@code Runnable} to an
 * {@code ExecutorService}, and the worker thread still sees the same traceId - without any manual
 * wrapping by the application.
 *
 * <p>Automatic bytecode weaving is intended for executor implementations loaded by the application
 * or plugin classloader. For JDK bootstrap executors created through {@link
 * java.util.concurrent.Executors}, use the API wrappers such as {@link
 * com.github.cc11001100.weavergirl.api.context.ContextExecutorService} or {@link
 * com.github.cc11001100.weavergirl.api.context.ContextScheduledExecutorService}.
 *
 * <h3>How it works</h3>
 *
 * <p>The plugin intercepts the submission methods and, in {@code before()}, replaces the first
 * argument with a context-propagating wrapper if it isn't already wrapped. The wrapper captures
 * {@link com.github.cc11001100.weavergirl.api.context.ContextSnapshot} at construction (i.e. at
 * submission time, on the submitting thread) and restores it inside the worker thread.
 *
 * <h3>Idempotency</h3>
 *
 * <p>To avoid double-wrapping when tasks pass through multiple executors, the interceptor checks
 * whether each task is already a {@code ContextRunnable} / {@code ContextCallable} and skips
 * wrapping in that case.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code enabled} — Enable/disable (default: true)
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
  private static final String SCHEDULED_EXECUTOR_SERVICE_INTERFACE =
      "java.util.concurrent.ScheduledExecutorService";

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
    registry.register(
        buildDefinition(
            "async-execute",
            EXECUTOR_INTERFACE,
            MethodMatcher.bySignature("execute", "java.lang.Runnable"),
            wrapInterceptor));
    // Also match ExecutorService implementations (they are Executors too)
    registry.register(
        buildDefinition(
            "async-execute-es",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature("execute", "java.lang.Runnable"),
            wrapInterceptor));

    // ExecutorService.submit(Runnable)
    registry.register(
        buildDefinition(
            "async-submit-runnable",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature("submit", "java.lang.Runnable"),
            wrapInterceptor));

    // ExecutorService.submit(Runnable, T)
    registry.register(
        buildDefinition(
            "async-submit-runnable-result",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature("submit", "java.lang.Runnable,java.lang.Object"),
            wrapInterceptor));

    // ExecutorService.submit(Callable)
    registry.register(
        buildDefinition(
            "async-submit-callable",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature("submit", "java.util.concurrent.Callable"),
            wrapInterceptor));

    // ExecutorService.invokeAll(Collection<Callable>)
    registry.register(
        buildDefinition(
            "async-invoke-all",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature("invokeAll", "java.util.Collection"),
            wrapInterceptor));

    // ExecutorService.invokeAll(Collection<Callable>, long, TimeUnit)
    registry.register(
        buildDefinition(
            "async-invoke-all-timeout",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature(
                "invokeAll", "java.util.Collection,long,java.util.concurrent.TimeUnit"),
            wrapInterceptor));

    // ExecutorService.invokeAny(Collection<Callable>)
    registry.register(
        buildDefinition(
            "async-invoke-any",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature("invokeAny", "java.util.Collection"),
            wrapInterceptor));

    // ExecutorService.invokeAny(Collection<Callable>, long, TimeUnit)
    registry.register(
        buildDefinition(
            "async-invoke-any-timeout",
            EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature(
                "invokeAny", "java.util.Collection,long,java.util.concurrent.TimeUnit"),
            wrapInterceptor));

    // ScheduledExecutorService.schedule(Runnable, long, TimeUnit)
    registry.register(
        buildDefinition(
            "async-schedule-runnable",
            SCHEDULED_EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature(
                "schedule", "java.lang.Runnable,long,java.util.concurrent.TimeUnit"),
            wrapInterceptor));

    // ScheduledExecutorService.schedule(Callable, long, TimeUnit)
    registry.register(
        buildDefinition(
            "async-schedule-callable",
            SCHEDULED_EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature(
                "schedule", "java.util.concurrent.Callable,long,java.util.concurrent.TimeUnit"),
            wrapInterceptor));

    // ScheduledExecutorService.scheduleAtFixedRate(Runnable, long, long, TimeUnit)
    registry.register(
        buildDefinition(
            "async-schedule-fixed-rate",
            SCHEDULED_EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature(
                "scheduleAtFixedRate",
                "java.lang.Runnable,long,long,java.util.concurrent.TimeUnit"),
            wrapInterceptor));

    // ScheduledExecutorService.scheduleWithFixedDelay(Runnable, long, long, TimeUnit)
    registry.register(
        buildDefinition(
            "async-schedule-fixed-delay",
            SCHEDULED_EXECUTOR_SERVICE_INTERFACE,
            MethodMatcher.bySignature(
                "scheduleWithFixedDelay",
                "java.lang.Runnable,long,long,java.util.concurrent.TimeUnit"),
            wrapInterceptor));

    if (log.isInfoEnabled()) {
      log.info(
          "[async-context-propagation] Registered Executor/ExecutorService/ScheduledExecutorService"
              + " hooks for ThreadContext propagation");
    }
  }

  private InterceptorDefinition buildDefinition(
      String name, String interfaceName, MethodMatcher methodMatcher, Interceptor interceptor) {
    // Run early so the wrapped task carries context before any other plugin's
    // after-callbacks observe the submission.
    // ARGUMENT_REWRITE mode: the transformer selects AsyncArgumentAdvice, which
    // binds @Advice.Argument(0, readOnly=false) so the Runnable/Callable we
    // swap in via setArgument(0, ...) actually reaches the executor's method
    // body. (InterceptAdvice's @Advice.AllArguments cannot write element
    // mutations back to parameter slots.)
    return new InterceptorDefinition(
        name,
        new Pointcut(ClassMatcher.byInterface(interfaceName), methodMatcher),
        interceptor,
        -100,
        InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE);
  }

  /**
   * Interceptor that wraps the first argument (a Runnable, Callable, or Collection of Callables) in
   * context-propagating wrappers.
   */
  static final class AsyncWrapInterceptor implements Interceptor {

    @Override
    public void before(MethodInvocation invocation) {
      if (invocation.getArguments().length == 0) {
        return;
      }
      Object arg = invocation.getArgument(0);
      if (arg instanceof ContextRunnable || arg instanceof ContextCallable) {
        // Already wrapped - avoid double-wrapping when tasks flow through
        // multiple executors.
        return;
      }
      if (arg instanceof Runnable && !(arg instanceof Callable)) {
        // Submitting a Runnable. Note: a Callable is also Runnable in some
        // adapters, so we explicitly exclude Callable here to keep the
        // return value semantics intact.
        invocation.setArgument(0, ContextPropagators.wrap((Runnable) arg));
      } else if (arg instanceof Callable) {
        invocation.setArgument(0, ContextPropagators.wrap((Callable<?>) arg));
      } else if (arg instanceof Collection) {
        Collection<?> collection = (Collection<?>) arg;
        if (containsOnlyCallables(collection)) {
          @SuppressWarnings({"unchecked", "rawtypes"})
          Collection<? extends Callable<Object>> callables = (Collection) collection;
          invocation.setArgument(0, ContextPropagators.wrapCallables(callables));
        }
      }
    }

    private boolean containsOnlyCallables(Collection<?> collection) {
      for (Object item : collection) {
        if (!(item instanceof Callable)) {
          return false;
        }
      }
      return true;
    }
  }
}
