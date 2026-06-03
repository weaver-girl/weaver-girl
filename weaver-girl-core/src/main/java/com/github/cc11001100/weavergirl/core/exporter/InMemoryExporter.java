package com.github.cc11001100.weavergirl.core.exporter;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Built-in exporter that stores events in memory.
 *
 * <p>Useful for testing, debugging, and real-time inspection via JMX or
 * health endpoints. Bounded to prevent memory leaks.</p>
 *
 * @since 1.2.0
 */
public class InMemoryExporter implements DataExporter {

    private static final int DEFAULT_MAX_EVENTS = 1000;

    private final CopyOnWriteArrayList<InterceptorEvent> events = new CopyOnWriteArrayList<>();
    private volatile int maxEvents = DEFAULT_MAX_EVENTS;

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public void export(InterceptorEvent event) {
        if (event == null) return;
        events.add(event);
        while (events.size() > maxEvents) {
            events.remove(0);
        }
    }

    @Override
    public void flush() {
        // No-op: in-memory doesn't buffer
    }

    @Override
    public void init(Map<String, String> config) {
        if (config != null) {
            String max = config.get("maxEvents");
            if (max != null) {
                try {
                    maxEvents = Math.max(1, Integer.parseInt(max.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    /**
     * Get all stored events.
     */
    public List<InterceptorEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * Get recent events.
     *
     * @param count number of recent events to return
     */
    public List<InterceptorEvent> getRecentEvents(int count) {
        int from = Math.max(0, events.size() - count);
        return Collections.unmodifiableList(new ArrayList<>(events.subList(from, events.size())));
    }

    /**
     * Get events filtered by type.
     */
    public List<InterceptorEvent> getEventsByType(String type) {
        List<InterceptorEvent> filtered = new ArrayList<>();
        for (InterceptorEvent e : events) {
            if (type.equals(e.getType())) {
                filtered.add(e);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    /**
     * Get event count.
     */
    public int size() {
        return events.size();
    }

    /**
     * Clear all stored events.
     */
    public void clear() {
        events.clear();
    }
}
