package com.github.cc11001100.weavergirl.api.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of propagatable in-process context.
 *
 * <p>Captures state from all registered {@link ContextPropagator}s (ThreadContext,
 * Tracer span, and any third-party propagators) into a single object that can be
 * passed through async boundaries. Restoring the snapshot activates all captured
 * state on the target thread; closing the resulting {@link ContextScope} restores
 * the target thread's prior state.</p>
 *
 * <p>{@link GlobalContext} is intentionally not captured into snapshots: global
 * values are already process-wide and visible to every thread.</p>
 *
 * @see ContextScope
 * @see ContextPropagatorRegistry
 * @see ContextPropagators
 * @since 1.6.0
 */
public final class ContextSnapshot {

    /**
     * Sentinel for an empty snapshot. An empty snapshot carries no propagator
     * entries, so {@link #restoreAll()} and {@link #cleanupAllReverse()} are
     * no-ops. However, {@link #empty()} dynamically builds a snapshot that
     * includes each registered propagator's "empty" snapshot so that activating
     * it clears all propagatable state (matching the old semantics where
     * {@code ThreadContext.restore(Collections.emptyMap())} cleared ThreadContext).
     */
    private static final ContextSnapshot EMPTY = new ContextSnapshot(null);

    private final List<Entry> entries;

    private static final class Entry {
        final ContextPropagator propagator;
        final ContextPropagator.Snapshot snapshot;

        Entry(ContextPropagator p, ContextPropagator.Snapshot s) {
            this.propagator = p;
            this.snapshot = s;
        }
    }

    private ContextSnapshot(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * Capture all registered propagatable context from the current thread.
     *
     * @return an immutable snapshot
     */
    public static ContextSnapshot capture() {
        List<ContextPropagator> propagators = ContextPropagatorRegistry.getAll();
        if (propagators.isEmpty()) {
            return EMPTY;
        }
        List<Entry> entries = new ArrayList<>(propagators.size());
        for (ContextPropagator p : propagators) {
            entries.add(new Entry(p, p.capture()));
        }
        return new ContextSnapshot(Collections.unmodifiableList(entries));
    }

    /**
     * Return an empty snapshot that clears all propagatable state when activated.
     *
     * <p>The snapshot contains each registered propagator's "empty" snapshot so
     * that {@link #activate()} clears ThreadContext, Tracer span, and any
     * third-party propagator state. For example, activating this snapshot calls
     * {@code ThreadContext.restore(emptyMap)} which clears all ThreadContext
     * entries, matching the old semantics.</p>
     *
     * @return an immutable empty snapshot
     */
    public static ContextSnapshot empty() {
        List<ContextPropagator> propagators = ContextPropagatorRegistry.getAll();
        if (propagators.isEmpty()) {
            return EMPTY;
        }
        List<Entry> entries = new ArrayList<>(propagators.size());
        for (ContextPropagator p : propagators) {
            // Use each propagator's EMPTY snapshot directly, not capture().
            // capture() reads the current thread which may not be empty.
            entries.add(new Entry(p, getEmptySnapshot(p)));
        }
        return new ContextSnapshot(Collections.unmodifiableList(entries));
    }

    /**
     * Return the propagator-specific "empty" snapshot. For built-in propagators
     * this is their static EMPTY constant; for third-party propagators we call
     * capture() after temporarily clearing ThreadContext, but since we cannot
     * guarantee a clean thread state, we use a sentinel EmptySnapshot instead.
     */
    private static ContextPropagator.Snapshot getEmptySnapshot(ContextPropagator p) {
        if (p instanceof ThreadContextPropagator) {
            return ThreadContextPropagator.ThreadContextSnapshot.EMPTY;
        }
        if (p instanceof TracerPropagator) {
            return TracerPropagator.SpanSnapshot.EMPTY;
        }
        // Third-party propagator: use a generic empty snapshot that is a no-op
        // on restore/cleanup. This means activating an empty snapshot won't
        // clear third-party state — those propagators should handle this in
        // their own restore() if the snapshot type is their EMPTY sentinel.
        return NO_OP_SNAPSHOT;
    }

    /** No-op snapshot for third-party propagators in empty() snapshots. */
    private static final ContextPropagator.Snapshot NO_OP_SNAPSHOT = new ContextPropagator.Snapshot() {};

    /**
     * Create a snapshot from an explicit ThreadContext map.
     *
     * <p>This is a convenience factory for callers that already have a
     * ThreadContext map. The resulting snapshot only carries ThreadContext
     * state (no Tracer span or third-party propagator state).</p>
     *
     * @param threadContext context values to restore into {@link ThreadContext}
     * @return an immutable snapshot
     */
    public static ContextSnapshot fromThreadContext(Map<String, Object> threadContext) {
        if (threadContext == null || threadContext.isEmpty()) {
            return EMPTY;
        }
        // Create a snapshot with only the ThreadContextPropagator entry
        Map<String, Object> immutable = Collections.unmodifiableMap(new HashMap<>(threadContext));
        ThreadContextPropagator.ThreadContextSnapshot tcSnapshot =
                new ThreadContextPropagator.ThreadContextSnapshot(immutable);
        Entry entry = new Entry(ContextPropagatorRegistry.get("thread-context"), tcSnapshot);
        return new ContextSnapshot(Collections.singletonList(entry));
    }

    /**
     * Return the captured ThreadContext values.
     *
     * <p>This is a convenience accessor for callers that only need the
     * ThreadContext portion of the snapshot. If the snapshot was created
     * via {@link #capture()}, the returned map reflects the ThreadContext
     * state at capture time. If created via {@link #fromThreadContext},
     * it returns the supplied map.</p>
     *
     * @return immutable ThreadContext map, or an empty map if not captured
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getThreadContext() {
        if (entries == null) {
            return Collections.emptyMap();
        }
        for (Entry e : entries) {
            if (e.propagator instanceof ThreadContextPropagator
                    && e.snapshot instanceof ThreadContextPropagator.ThreadContextSnapshot) {
                return ((ThreadContextPropagator.ThreadContextSnapshot) e.snapshot).captured;
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Return whether this snapshot contains no propagatable values.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return entries == null || entries.isEmpty();
    }

    /**
     * Activate this snapshot on the current thread.
     *
     * <p>The returned scope restores the previous thread context when closed.
     * Use try-with-resources whenever possible.</p>
     *
     * @return active scope that must be closed
     */
    public ContextScope activate() {
        return ContextScope.activate(this);
    }

    // ---- Package-private methods used by ContextScope ----

    /**
     * Restore all captured state, in propagator registration order.
     * Called by {@link ContextScope} on the worker thread.
     *
     * <p>When {@code entries == null} (the EMPTY sentinel, used only when no
     * propagators are registered), clears ThreadContext as a baseline — this
     * preserves the old semantics where activating an empty snapshot cleared
     * ThreadContext. When propagators <em>are</em> registered, {@link #empty()}
     * builds a snapshot with each propagator's empty snapshot instead of using
     * the EMPTY sentinel.</p>
     */
    void restoreAll() {
        if (entries == null) {
            // No propagators registered — fall back to clearing ThreadContext
            ThreadContext.restore(null);
            return;
        }
        for (Entry e : entries) {
            e.propagator.restore(e.snapshot);
        }
    }

    /**
     * Cleanup all state, in REVERSE propagator registration order (LIFO unwind).
     * Called by {@link ContextScope} on the worker thread in a finally block.
     * Each propagator receives the snapshot it captured on the worker thread
     * (i.e. the "previous" state) so it can restore the worker's prior state.
     */
    void cleanupAllReverse() {
        if (entries == null) {
            return;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            entries.get(i).propagator.cleanup(entries.get(i).snapshot);
        }
    }
}
