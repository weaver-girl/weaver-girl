package com.github.cc11001100.weavergirl.api.context;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * {@link Callable} wrapper that captures the {@link ThreadContext} at creation time
 * and restores it in the executing thread.
 *
 * <p>Use this wrapper when submitting work to an {@link java.util.concurrent.ExecutorService}
 * that returns a value, to automatically propagate the calling thread's context into
 * the worker thread. The original context of the worker thread is saved before
 * restoration and restored after execution, ensuring no side effects on the worker
 * thread.</p>
 *
 * <h3>When to use</h3>
 * <p>Use this class when you need to propagate {@link ThreadContext} across thread
 * boundaries for a task that returns a value (i.e., a {@code Callable}). For
 * fire-and-forget tasks with no return value, use {@link ContextRunnable} instead.</p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * // In an interceptor before() callback:
 * ThreadContext.put("traceId", currentTraceId);
 *
 * // Submit work that needs access to the trace ID and returns a result:
 * Future&lt;String&gt; future = executor.submit(new ContextCallable&lt;&gt;(() -&gt; {
 *     String traceId = ThreadContext.get("traceId"); // available here
 *     return processWithTrace(traceId);
 * }));</pre>
 *
 * @see ThreadContext
 * @see ContextRunnable
 * @since 1.0.0
 */
public class ContextCallable<V> implements Callable<V> {

    private final Callable<V> delegate;
    private final ContextSnapshot capturedContext;

    /**
     * Creates a new ContextCallable that wraps the given delegate.
     *
     * <p>The current thread's context is captured at construction time.
     * When {@link #call()} is executed (possibly in another thread), the captured
     * context is restored before the delegate runs, and the executing thread's
     * original context is restored afterward.</p>
     *
     * @param delegate the Callable to wrap; must not be null
     */
    public ContextCallable(Callable<V> delegate) {
        this(delegate, ContextSnapshot.capture());
    }

    /**
     * Creates a new ContextCallable with an explicit snapshot.
     *
     * @param delegate the Callable to wrap; must not be null
     * @param snapshot the context snapshot to activate while calling
     * @since 1.6.0
     */
    public ContextCallable(Callable<V> delegate, ContextSnapshot snapshot) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.capturedContext = snapshot != null ? snapshot : ContextSnapshot.empty();
    }

    /**
     * Executes the delegate callable with the captured context restored.
     *
     * <p>The executing thread's original context is saved, the captured context
     * is restored, the delegate runs, and then the original context is restored
     * in a finally block to ensure cleanup even if the delegate throws.</p>
     *
     * @return the result of the delegate callable
     * @throws Exception if the delegate callable throws an exception
     */
    @Override
    public V call() throws Exception {
        try (ContextScope ignored = capturedContext.activate()) {
            return delegate.call();
        }
    }
}
