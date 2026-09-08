package com.github.cc11001100.weavergirl.core.event;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
}
