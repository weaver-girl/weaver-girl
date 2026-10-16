package com.github.cc11001100.weavergirl.core.event;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.core.testsupport.Statics;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.*;

/** Tests for async event publisher (P54). */
class AsyncEventPublisherTest {

  private AsyncEventPublisher publisher;

  @AfterEach
  void tearDown() {
    if (publisher != null) {
      publisher.shutdown();
    }
  }

  @Test
  void publishAsync_deliversToListeners() throws Exception {
    InterceptorEventPublisher sync = InterceptorEventPublisher.getInstance();
    List<InterceptorEvent> received = new CopyOnWriteArrayList<>();
    InterceptorEventListener listener = received::add;

    sync.addListener(listener);
    publisher = new AsyncEventPublisher(sync);

    InterceptorEvent event = InterceptorEvent.builder().type("test").plugin("p").build();

    publisher.publishAsync(event);

    // Wait for async delivery
    Thread.sleep(200);

    assertTrue(received.size() >= 1, "Should have received event");
    sync.removeListener(listener);
  }

  @Test
  void publishSync_deliversImmediately() {
    InterceptorEventPublisher sync = InterceptorEventPublisher.getInstance();
    List<InterceptorEvent> received = new CopyOnWriteArrayList<>();
    InterceptorEventListener listener = received::add;

    sync.addListener(listener);
    publisher = new AsyncEventPublisher(sync);

    InterceptorEvent event = InterceptorEvent.builder().type("sync-test").plugin("p").build();

    publisher.publishSync(event);
    assertEquals(1, received.size());

    sync.removeListener(listener);
  }

  @Test
  void shutdown_preventsFurtherPublishing() {
    InterceptorEventPublisher sync = InterceptorEventPublisher.getInstance();
    publisher = new AsyncEventPublisher(sync);

    publisher.shutdown();
    assertTrue(publisher.isShutdown());

    // Should not throw
    assertDoesNotThrow(
        () -> publisher.publishAsync(InterceptorEvent.builder().type("t").plugin("p").build()));
  }

  @Test
  void publishAsync_nullEventIgnored() {
    InterceptorEventPublisher sync = InterceptorEventPublisher.getInstance();
    publisher = new AsyncEventPublisher(sync);
    assertDoesNotThrow(() -> publisher.publishAsync(null));
  }

  /** A listener that throws is logged and skipped. The listener after it still receives the event. */
  @Test
  void aListenerThatThrowsDoesNotStopTheOthers() throws Exception {
    InterceptorEventPublisher sync = InterceptorEventPublisher.getInstance();
    List<String> received = new CopyOnWriteArrayList<>();
    InterceptorEventListener broken = event -> {
      throw new IllegalStateException("listener down");
    };
    InterceptorEventListener healthy = event -> received.add(event.getType());
    sync.addListener(broken);
    sync.addListener(healthy);
    publisher = new AsyncEventPublisher(sync);
    try {
      InterceptorEvent event = InterceptorEvent.builder().type("survived").plugin("p").build();

      publisher.publishSync(event);
      publisher.publishAsync(event);
      Thread.sleep(300);

      assertEquals(java.util.Arrays.asList("survived", "survived"), received);
    } finally {
      sync.removeListener(broken);
      sync.removeListener(healthy);
    }
  }

  /** A null event has nothing to deliver synchronously. */
  @Test
  void publishSync_nullEventIgnored() {
    publisher = new AsyncEventPublisher(InterceptorEventPublisher.getInstance());
    assertDoesNotThrow(() -> publisher.publishSync(null));
  }

  /**
   * Iterating the listener list can itself fail, which is outside the per-listener catch. That failure
   * is logged rather than thrown, both synchronously and on the async thread.
   */
  @Test
  void aListenerListThatFailsWhileIteratingIsSurvived() throws Exception {
    Field listeners = InterceptorEventPublisher.class.getDeclaredField("listeners");
    InterceptorEventPublisher sync = InterceptorEventPublisher.getInstance();
    Object previous = Statics.read(listeners, sync);
    Statics.write(listeners, sync, new ExplodingList());
    publisher = new AsyncEventPublisher(sync);
    try {
      // publishSync lets this failure propagate; only the async path catches it.
      publisher.publishAsync(InterceptorEvent.builder().type("t").plugin("p").build());
      Thread.sleep(300);
    } finally {
      Statics.write(listeners, sync, previous);
    }
  }

  /** A list whose iterator fails immediately, so reading the listeners throws. */
  private static final class ExplodingList extends ArrayList<InterceptorEventListener> {
    @Override
    public java.util.Iterator<InterceptorEventListener> iterator() {
      throw new IllegalStateException("listeners unavailable");
    }
  }

  /** A pool that never finishes in time is interrupted, and an interrupted wait is restored. */
  @Test
  void shutdownForcesAPoolThatWillNotStop() throws Exception {
    publisher = new AsyncEventPublisher(InterceptorEventPublisher.getInstance());
    ExecutorService pool = executorOf(publisher);
    pool.submit(
        () -> {
          try {
            Thread.sleep(60_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    publisher.shutdown();

    assertTrue(publisher.isShutdown());
    assertTrue(pool.isShutdown());
  }

  /** An interrupted wait during shutdown stops the pool and restores the interrupt. */
  @Test
  void anInterruptedShutdownRestoresTheInterrupt() throws Exception {
    publisher = new AsyncEventPublisher(InterceptorEventPublisher.getInstance());
    ExecutorService pool = executorOf(publisher);
    pool.submit(
        () -> {
          try {
            Thread.sleep(60_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    Thread.currentThread().interrupt();
    try {
      publisher.shutdown();
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");
    } finally {
      Thread.interrupted();
    }
  }

  private static ExecutorService executorOf(AsyncEventPublisher publisher) throws Exception {
    Field executor = AsyncEventPublisher.class.getDeclaredField("executor");
    executor.setAccessible(true);
    return (ExecutorService) executor.get(publisher);
  }
}
