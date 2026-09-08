package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Async wrapper around {@link InterceptorEventPublisher}.
 *
 * <p>Delivers events to listeners asynchronously via a dedicated thread pool, preventing listener
 * execution from blocking the interception path.
 *
 * @since 1.1.0
 */
public class AsyncEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(AsyncEventPublisher.class);

  private final ExecutorService executor;
  private final InterceptorEventPublisher delegate;
  private volatile boolean shutdown = false;

  /**
   * Create an async publisher wrapping the given synchronous publisher.
   *
   * @param delegate the synchronous event publisher
   */
  public AsyncEventPublisher(InterceptorEventPublisher delegate) {
    this.delegate = delegate;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "weaver-event-publisher");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Publish an event asynchronously.
   *
   * @param event the event to publish
   */
  public void publishAsync(InterceptorEvent event) {
    if (shutdown || event == null) return;
    executor.submit(
        () -> {
          try {
            for (InterceptorEventListener listener : delegate.getListeners()) {
              try {
                listener.onEvent(event);
              } catch (Exception e) {
                log.warn("[AsyncEventPublisher] Listener failed: {}", e.getMessage());
              }
            }
          } catch (Exception e) {
            log.warn("[AsyncEventPublisher] Failed to publish event: {}", e.getMessage());
          }
        });
  }

  /**
   * Publish an event synchronously (for critical events that must be delivered immediately).
   *
   * @param event the event to publish
   */
  public void publishSync(InterceptorEvent event) {
    if (event == null) return;
    for (InterceptorEventListener listener : delegate.getListeners()) {
      try {
        listener.onEvent(event);
      } catch (Exception e) {
        log.warn("[AsyncEventPublisher] Listener failed: {}", e.getMessage());
      }
    }
  }

  /** Shut down the async publisher and wait for pending events. */
  public void shutdown() {
    shutdown = true;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** Check if the publisher is shut down. */
  public boolean isShutdown() {
    return shutdown;
  }
}
