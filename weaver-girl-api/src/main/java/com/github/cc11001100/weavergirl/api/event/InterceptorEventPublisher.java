package com.github.cc11001100.weavergirl.api.event;

import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publisher for interceptor events. Allows plugins to emit structured events that can be consumed
 * by registered listeners for metrics export, trace propagation, structured logging, etc.
 *
 * <p>Thread-safe. Listeners are invoked synchronously on the calling thread (the intercepted
 * method's thread). Keep listener implementations fast and non-blocking.
 *
 * <p>Usage in a plugin:
 *
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

  private static final InterceptorEventPublisher INSTANCE = new InterceptorEventPublisher();

  private final CopyOnWriteArrayList<InterceptorEventListener> listeners =
      new CopyOnWriteArrayList<>();

  private InterceptorEventPublisher() {}

  /**
   * Returns the singleton publisher instance.
   *
   * @return the shared publisher, never null
   */
  public static InterceptorEventPublisher getInstance() {
    return INSTANCE;
  }

  /** Add a listener for interceptor events. */
  public void addListener(InterceptorEventListener listener) {
    listeners.add(listener);
  }

  /** Remove a listener. */
  public void removeListener(InterceptorEventListener listener) {
    listeners.remove(listener);
  }

  /** Get all registered listeners. */
  public List<InterceptorEventListener> getListeners() {
    return Collections.unmodifiableList(listeners);
  }

  /**
   * Publish an event to all registered listeners and active exporters. If a listener or exporter
   * throws an exception, it is printed to stderr but does not prevent other consumers from
   * receiving the event.
   *
   * @param event the event to publish
   */
  public void publish(InterceptorEvent event) {
    for (InterceptorEventListener listener : listeners) {
      try {
        listener.onEvent(event);
      } catch (Exception e) {
        System.err.println("[weaver-girl] Event listener threw exception: " + e.getMessage());
      }
    }
    try {
      ExporterRegistry.exportEvent(event);
    } catch (Exception e) {
      System.err.println("[weaver-girl] ExporterRegistry threw exception: " + e.getMessage());
    }
  }
}
