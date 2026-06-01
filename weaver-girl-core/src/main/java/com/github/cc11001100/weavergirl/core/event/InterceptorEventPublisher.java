package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publisher for interceptor events.
 * Allows plugins to emit structured events that can be consumed
 * by registered listeners for metrics export, trace propagation,
 * structured logging, etc.
 *
 * <p>Thread-safe. Listeners are invoked synchronously on the calling
 * thread (the intercepted method's thread). Keep listener implementations
 * fast and non-blocking.</p>
 *
 * <p>Usage in a plugin:</p>
 * <pre>
 * InterceptorEventPublisher.getInstance().publish(
 *     InterceptorEvent.builder()
 *         .type("slow-query")
 *         .plugin("jdbc")
 *         .className(inv.getTargetClass().getSimpleName())
 *         .methodName(inv.getMethodName())
 *         .durationMs(elapsedMs)
 *         .attribute("sql", sql)
 *         .build()
 * );
 * </pre>
 */
public class InterceptorEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InterceptorEventPublisher.class);

    private static final InterceptorEventPublisher INSTANCE = new InterceptorEventPublisher();

    private final CopyOnWriteArrayList<InterceptorEventListener> listeners = new CopyOnWriteArrayList<>();

    private InterceptorEventPublisher() {}

    public static InterceptorEventPublisher getInstance() {
        return INSTANCE;
    }

    /**
     * Add a listener for interceptor events.
     */
    public void addListener(InterceptorEventListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     */
    public void removeListener(InterceptorEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Get all registered listeners.
     */
    public List<InterceptorEventListener> getListeners() {
        return java.util.Collections.unmodifiableList(listeners);
    }

    /**
     * Publish an event to all registered listeners.
     * If a listener throws an exception, it is logged but does not
     * prevent other listeners from receiving the event.
     *
     * @param event the event to publish
     */
    public void publish(InterceptorEvent event) {
        for (InterceptorEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("Event listener threw exception: {}", e.getMessage());
            }
        }
    }
}
