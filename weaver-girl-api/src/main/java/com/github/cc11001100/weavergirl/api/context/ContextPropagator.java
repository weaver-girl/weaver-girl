package com.github.cc11001100.weavergirl.api.context;

/**
 * SPI for propagating thread-local state across thread boundaries.
 *
 * <p>Implementations capture state on the <em>submitting</em> thread, restore it
 * on the <em>worker</em> thread before the task runs, and clean up / restore the
 * worker's prior state in a {@code finally} block after the task completes.</p>
 *
 * <h3>Lifecycle (called by {@link ContextSnapshot} / {@link ContextScope})</h3>
 * <ol>
 *   <li><b>capture()</b> — called on the submitting thread at snapshot construction
 *       time. Returns an opaque {@link Snapshot}.</li>
 *   <li><b>restore(Snapshot)</b> — called on the worker thread, before the
 *       delegate task runs. Installs the captured state into the current thread.</li>
 *   <li><b>cleanup(Snapshot)</b> — called on the worker thread in a {@code finally}
 *       block after the delegate returns or throws. The {@code previous} argument
 *       is the snapshot captured on the worker thread right before {@code restore};
 *       implementations use it to restore the worker's prior state or clear it.</li>
 * </ol>
 *
 * <h3>Registration</h3>
 * <p>Register implementations via {@link ContextPropagatorRegistry#register}.
 * Built-in propagators for {@link ThreadContext} and {@link Tracer} span are
 * registered by default.</p>
 *
 * @see ContextPropagatorRegistry
 * @see ContextSnapshot
 * @see ContextScope
 * @since 1.7.0
 */
public interface ContextPropagator {

    /**
     * Opaque snapshot of thread-local state captured on one thread, to be
     * restored on another. Implementations are expected to be effectively
     * immutable.
     */
    interface Snapshot {
    }

    /**
     * Stable, non-null identifier. Used for dedup registration and ordering.
     * Convention: {@code "<pluginName>.<propagatorName>"} (e.g.
     * {@code "thread-context"}, {@code "tracer-span"}).
     *
     * @return the propagator name
     */
    String name();

    /**
     * Capture the current thread's state. Called on the submitting thread.
     * Must never return null — return an empty Snapshot when there is nothing
     * to propagate.
     *
     * @return an immutable snapshot of the current thread's state
     */
    Snapshot capture();

    /**
     * Restore the captured state into the current (worker) thread, before the
     * task runs. Called on the worker thread.
     *
     * @param snapshot the snapshot returned by {@link #capture()}
     */
    void restore(Snapshot snapshot);

    /**
     * Restore the worker thread's <em>prior</em> state after the task completes,
     * in a finally block. Called on the worker thread.
     *
     * <p>The {@code previous} argument is what {@link #capture()} returned when
     * called on the worker thread right before {@link #restore}. Implementations
     * that want symmetric restore pass {@code previous} to the same restore
     * primitive; implementations that prefer clear-on-exit ignore {@code previous}
     * and call their clear primitive.</p>
     *
     * @param previous the worker thread's prior state snapshot
     */
    void cleanup(Snapshot previous);
}
