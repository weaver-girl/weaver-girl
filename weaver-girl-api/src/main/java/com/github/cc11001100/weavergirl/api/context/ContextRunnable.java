package com.github.cc11001100.weavergirl.api.context;

import java.util.Objects;

/**
 * {@link Runnable} wrapper that captures the {@link ThreadContext} at creation time and restores it
 * in the executing thread.
 *
 * <p>Use this wrapper when submitting work to an {@link java.util.concurrent.Executor} or {@link
 * java.util.concurrent.ExecutorService} to automatically propagate the calling thread's context
 * into the worker thread. The original context of the worker thread is saved before restoration and
 * restored after execution, ensuring no side effects on the worker thread.
 *
 * <h3>When to use</h3>
 *
 * <p>Use this class when you need to propagate {@link ThreadContext} across thread boundaries for a
 * fire-and-forget task (i.e., a {@code Runnable} with no return value). For tasks that return a
 * value, use {@link ContextCallable} instead.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * // In an interceptor before() callback:
 * ThreadContext.put("traceId", currentTraceId);
 *
 * // Submit work that needs access to the trace ID:
 * executor.submit(new ContextRunnable(() -&gt; {
 *     String traceId = ThreadContext.get("traceId"); // available here
 *     processWithTrace(traceId);
 * }));</pre>
 *
 * @see ThreadContext
 * @see ContextCallable
 * @since 1.0.0
 */
public class ContextRunnable implements Runnable {

  private final Runnable delegate;
  private final ContextSnapshot capturedContext;

  /**
   * Creates a new ContextRunnable that wraps the given delegate.
   *
   * <p>The current thread's context is captured at construction time. When {@link #run()} is
   * executed (possibly in another thread), the captured context is restored before the delegate
   * runs, and the executing thread's original context is restored afterward.
   *
   * @param delegate the Runnable to wrap; must not be null
   */
  public ContextRunnable(Runnable delegate) {
    this(delegate, ContextSnapshot.capture());
  }

  /**
   * Creates a new ContextRunnable with an explicit snapshot.
   *
   * @param delegate the Runnable to wrap; must not be null
   * @param snapshot the context snapshot to activate while running
   * @since 1.6.0
   */
  public ContextRunnable(Runnable delegate, ContextSnapshot snapshot) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.capturedContext = snapshot != null ? snapshot : ContextSnapshot.empty();
  }

  /**
   * Executes the delegate runnable with the captured context restored.
   *
   * <p>The executing thread's original context is saved, the captured context is restored, the
   * delegate runs, and then the original context is restored in a finally block to ensure cleanup
   * even if the delegate throws.
   */
  @Override
  public void run() {
    try (ContextScope ignored = capturedContext.activate()) {
      delegate.run();
    }
  }
}
