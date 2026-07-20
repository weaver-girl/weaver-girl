package com.github.cc11001100.weavergirl.api.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ContextPropagator priority ordering.
 *
 * <p>Covers: built-in priority values, custom propagator insertion order,
 * default priority, capture sequence follows priority, and same-priority
 * tiebreaker by registration order.</p>
 */
class ContextPropagatorPriorityTest {

    @AfterEach
    void tearDown() {
        ContextPropagatorRegistry.resetForTest();
    }

    @Test
    void builtInPropagatorsHaveCorrectPriorityOrder() {
        List<ContextPropagator> all = ContextPropagatorRegistry.getAll();
        // ThreadContextPropagator (-200) should come before TracerPropagator (-100)
        int tcIdx = -1, tracerIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof ThreadContextPropagator) tcIdx = i;
            if (all.get(i) instanceof TracerPropagator) tracerIdx = i;
        }
        assertTrue(tcIdx >= 0 && tracerIdx >= 0, "Both built-in propagators should be registered");
        assertTrue(tcIdx < tracerIdx,
                "ThreadContextPropagator should come before TracerPropagator");
    }

    @Test
    void customPropagatorWithLowerPriorityComesFirst() {
        ContextPropagator custom = new ContextPropagator() {
            @Override public String name() { return "custom-low"; }
            @Override public Snapshot capture() { return new Snapshot() {}; }
            @Override public void restore(Snapshot s) {}
            @Override public void cleanup(Snapshot p) {}
            @Override public int priority() { return -150; }
        };
        ContextPropagatorRegistry.register(custom);

        List<ContextPropagator> all = ContextPropagatorRegistry.getAll();
        int tcIdx = -1, customIdx = -1, tracerIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof ThreadContextPropagator) tcIdx = i;
            if ("custom-low".equals(all.get(i).name())) customIdx = i;
            if (all.get(i) instanceof TracerPropagator) tracerIdx = i;
        }
        // Expected: TC(-200) < custom(-150) < Tracer(-100)
        assertTrue(tcIdx < customIdx, "TC should be before custom");
        assertTrue(customIdx < tracerIdx, "custom should be before Tracer");
    }

    @Test
    void defaultPriorityIsZero() {
        ContextPropagator defaultP = new ContextPropagator() {
            @Override public String name() { return "default-p"; }
            @Override public Snapshot capture() { return new Snapshot() {}; }
            @Override public void restore(Snapshot s) {}
            @Override public void cleanup(Snapshot p) {}
        };
        assertEquals(0, defaultP.priority(), "Default priority should be 0");
    }

    @Test
    void priorityOrderAffectsCaptureSequence() {
        List<String> captureOrder = new ArrayList<>();
        ContextPropagator p1 = new ContextPropagator() {
            @Override public String name() { return "p1"; }
            @Override public Snapshot capture() { captureOrder.add("p1"); return new Snapshot() {}; }
            @Override public void restore(Snapshot s) {}
            @Override public void cleanup(Snapshot p) {}
            @Override public int priority() { return 10; }
        };
        ContextPropagator p2 = new ContextPropagator() {
            @Override public String name() { return "p2"; }
            @Override public Snapshot capture() { captureOrder.add("p2"); return new Snapshot() {}; }
            @Override public void restore(Snapshot s) {}
            @Override public void cleanup(Snapshot p) {}
            @Override public int priority() { return -10; }
        };
        ContextPropagatorRegistry.register(p1);
        ContextPropagatorRegistry.register(p2);

        ContextSnapshot.capture();

        // p2 (-10) should be captured before p1 (10)
        assertTrue(captureOrder.indexOf("p2") < captureOrder.indexOf("p1"),
                "Lower priority propagator should capture first");
    }

    @Test
    void samePriorityFallsBackToRegistrationOrder() {
        List<String> order = new ArrayList<>();
        ContextPropagator a = new ContextPropagator() {
            @Override public String name() { return "a"; }
            @Override public Snapshot capture() { order.add("a"); return new Snapshot() {}; }
            @Override public void restore(Snapshot s) {}
            @Override public void cleanup(Snapshot p) {}
        };
        ContextPropagator b = new ContextPropagator() {
            @Override public String name() { return "b"; }
            @Override public Snapshot capture() { order.add("b"); return new Snapshot() {}; }
            @Override public void restore(Snapshot s) {}
            @Override public void cleanup(Snapshot p) {}
        };
        ContextPropagatorRegistry.register(a);
        ContextPropagatorRegistry.register(b);

        ContextSnapshot.capture();

        // a and b have default priority 0; built-ins (-200/-100) capture first,
        // then a, then b (registration order tiebreaker)
        int aIdx = order.indexOf("a");
        int bIdx = order.indexOf("b");
        assertTrue(aIdx < bIdx, "Same priority should use registration order");
    }
}
