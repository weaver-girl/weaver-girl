package com.github.cc11001100.weavergirl.api.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide registry of {@link ContextPropagator}s.
 *
 * <p>{@link ContextSnapshot} and {@link ContextScope} iterate the registered
 * propagators to capture/restore all thread-local state (ThreadContext, Tracer
 * span, and any third-party ThreadLocals) in a single, uniform cycle.</p>
 *
 * <h3>Built-in propagators</h3>
 * <p>Registered in a {@code static} initializer block:</p>
 * <ul>
 *   <li>{@link ThreadContextPropagator} — name {@code "thread-context"}, priority -200</li>
 *   <li>{@link TracerPropagator} — name {@code "tracer-span"}, priority -100</li>
 * </ul>
 *
 * <h3>Ordering</h3>
 * <p>Propagators are ordered by {@link ContextPropagator#priority()} (ascending).
 * Capture and restore iterate in priority order (lowest first); cleanup iterates
 * in <em>reverse</em> priority order (highest first) so that nested restores
 * unwind symmetrically. When two propagators have the same priority, registration
 * order is used as a tiebreaker (stable sort).</p>
 *
 * @see ContextPropagator
 * @see ContextSnapshot
 * @since 1.7.0
 */
public final class ContextPropagatorRegistry {

    private static final ConcurrentHashMap<String, ContextPropagator> REGISTRY =
            new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ContextPropagator> ORDERED =
            new CopyOnWriteArrayList<>();

    /**
     * Comparator: ascending by priority. {@link CopyOnWriteArrayList#sort}
     * is stable, so equal priorities keep registration order.
     */
    private static final Comparator<ContextPropagator> BY_PRIORITY =
            Comparator.comparingInt(ContextPropagator::priority);

    static {
        register(new ThreadContextPropagator());
        register(new TracerPropagator());
        register(new TenantContextPropagator());
    }

    private ContextPropagatorRegistry() {
    }

    /**
     * Register a propagator. If a propagator with the same
     * {@link ContextPropagator#name()} is already registered, it is replaced.
     * The registry is re-sorted by priority after each registration.
     *
     * @param propagator the propagator to register; must not be null
     */
    public static void register(ContextPropagator propagator) {
        if (propagator == null || propagator.name() == null) {
            return;
        }
        ContextPropagator existing = REGISTRY.put(propagator.name(), propagator);
        if (existing != null) {
            ORDERED.remove(existing);
        }
        ORDERED.add(propagator);
        // Re-sort by priority (stable — equal priorities keep registration order)
        ORDERED.sort(BY_PRIORITY);
    }

    /**
     * Remove a propagator by name.
     *
     * @param name the propagator name
     * @return true if a propagator was removed
     */
    public static boolean unregister(String name) {
        ContextPropagator removed = REGISTRY.remove(name);
        if (removed != null) {
            ORDERED.remove(removed);
            return true;
        }
        return false;
    }

    /**
     * All registered propagators in priority order (unmodifiable).
     *
     * @return unmodifiable list of propagators sorted by priority
     */
    public static List<ContextPropagator> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(ORDERED));
    }

    /**
     * Look up a propagator by name.
     *
     * @param name the propagator name
     * @return the propagator, or null if not found
     */
    public static ContextPropagator get(String name) {
        return name != null ? REGISTRY.get(name) : null;
    }

    /**
     * For test isolation only — clears all propagators and re-registers
     * built-ins.
     */
    static void resetForTest() {
        REGISTRY.clear();
        ORDERED.clear();
        register(new ThreadContextPropagator());
        register(new TracerPropagator());
        register(new TenantContextPropagator());
    }
}
