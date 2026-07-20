package com.github.cc11001100.weavergirl.api.context;

import java.util.Collections;
import java.util.Map;

/**
 * Built-in {@link ContextPropagator} that propagates {@link ThreadContext}
 * across thread boundaries.
 *
 * <p>Capture/restore/cleanup semantics are identical to the previous
 * hard-coded logic in {@link ContextRunnable}: capture returns an unmodifiable
 * snapshot, restore does a full replace, cleanup restores the worker thread's
 * prior context symmetrically.</p>
 *
 * @see ThreadContext
 * @since 1.7.0
 */
public class ThreadContextPropagator implements ContextPropagator {

    static final class ThreadContextSnapshot implements Snapshot {
        final Map<String, Object> captured;
        static final ThreadContextSnapshot EMPTY =
                new ThreadContextSnapshot(Collections.<String, Object>emptyMap());

        ThreadContextSnapshot(Map<String, Object> captured) {
            this.captured = captured;
        }
    }

    @Override
    public String name() {
        return "thread-context";
    }

    @Override
    public Snapshot capture() {
        Map<String, Object> m = ThreadContext.capture();
        return m.isEmpty() ? ThreadContextSnapshot.EMPTY : new ThreadContextSnapshot(m);
    }

    @Override
    public void restore(Snapshot snapshot) {
        if (snapshot instanceof ThreadContextSnapshot) {
            ThreadContext.restore(((ThreadContextSnapshot) snapshot).captured);
        }
    }

    @Override
    public void cleanup(Snapshot previous) {
        // Symmetric restore of worker's prior ThreadContext — exactly matches
        // the previous ContextScope.close() behavior: ThreadContext.restore(previous)
        restore(previous);
    }

    @Override
    public int priority() {
        // Must restore before any propagator that reads ThreadContext bridge
        // keys (TracerPropagator, TenantContextPropagator, MdcPropagator).
        return -200;
    }
}
