package com.github.cc11001100.weavergirl.api.context;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AutoCloseable scope for temporarily activating a {@link ContextSnapshot}.
 *
 * <p>Creating a scope captures the current thread's previous context (from all
 * registered {@link ContextPropagator}s), restores the supplied snapshot, and
 * then restores the previous context when closed. This prevents ThreadLocal
 * leaks in executor worker threads and nested interceptor flows.</p>
 *
 * @see ContextSnapshot#activate()
 * @see ContextPropagators#scope(ContextSnapshot)
 * @since 1.6.0
 */
public final class ContextScope implements AutoCloseable {

    private final ContextSnapshot previous;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ContextScope(ContextSnapshot snapshot) {
        // Capture the worker thread's current state BEFORE restoring the
        // supplied snapshot. This "previous" snapshot is used by cleanup to
        // restore the worker's prior state symmetrically.
        this.previous = ContextSnapshot.capture();
        snapshot.restoreAll();
    }

    /**
     * Activate a snapshot on the current thread.
     *
     * @param snapshot snapshot to activate; null is treated as empty
     * @return active scope
     */
    public static ContextScope activate(ContextSnapshot snapshot) {
        return new ContextScope(snapshot != null ? snapshot : ContextSnapshot.empty());
    }

    /**
     * Restore the context that was active before this scope.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            previous.cleanupAllReverse();
        }
    }
}
