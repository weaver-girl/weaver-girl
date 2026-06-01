package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InterceptorEventPublisherTest {

    @BeforeEach
    void clearListeners() {
        InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
        for (InterceptorEventListener listener : publisher.getListeners()) {
            publisher.removeListener(listener);
        }
    }

    @Test
    void publish_deliversEventToListener() {
        InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
        AtomicReference<InterceptorEvent> received = new AtomicReference<>();
        publisher.addListener(received::set);

        InterceptorEvent event = InterceptorEvent.builder()
                .type("test")
                .plugin("test")
                .className("Test")
                .methodName("test")
                .build();

        publisher.publish(event);

        assertNotNull(received.get());
        assertEquals("test", received.get().getType());
    }

    @Test
    void publish_multipleListeners_allReceiveEvent() {
        InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
        AtomicReference<InterceptorEvent> received1 = new AtomicReference<>();
        AtomicReference<InterceptorEvent> received2 = new AtomicReference<>();
        publisher.addListener(received1::set);
        publisher.addListener(received2::set);

        InterceptorEvent event = InterceptorEvent.builder()
                .type("multi-test")
                .plugin("test")
                .build();

        publisher.publish(event);

        assertNotNull(received1.get());
        assertNotNull(received2.get());
    }

    @Test
    void removeListener_noLongerReceivesEvents() {
        InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
        AtomicReference<InterceptorEvent> received = new AtomicReference<>();
        InterceptorEventListener listener = received::set;
        publisher.addListener(listener);
        publisher.removeListener(listener);

        InterceptorEvent event = InterceptorEvent.builder()
                .type("removed-test")
                .plugin("test")
                .build();

        publisher.publish(event);

        assertNull(received.get());
    }

    @Test
    void listenerException_doesNotBlockOtherListeners() {
        InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
        AtomicReference<InterceptorEvent> received = new AtomicReference<>();
        publisher.addListener(e -> { throw new RuntimeException("broken"); });
        publisher.addListener(received::set);

        InterceptorEvent event = InterceptorEvent.builder()
                .type("error-test")
                .plugin("test")
                .build();

        publisher.publish(event);

        assertNotNull(received.get(), "Second listener should still receive event");
    }

    @Test
    void eventBuilder_createsEventWithAttributes() {
        InterceptorEvent event = InterceptorEvent.builder()
                .type("slow-query")
                .plugin("jdbc")
                .className("PgStatement")
                .methodName("execute")
                .durationMs(2500)
                .attribute("sql", "SELECT 1")
                .attribute("rows", "42")
                .build();

        assertEquals("slow-query", event.getType());
        assertEquals("jdbc", event.getPlugin());
        assertEquals(2500, event.getDurationMs());
        assertEquals(2, event.getAttributes().size());
        assertEquals("SELECT 1", event.getAttributes().get("sql"));
        assertEquals("42", event.getAttributes().get("rows"));
    }

    @Test
    void eventToMap_producesStructuredMap() {
        InterceptorEvent event = InterceptorEvent.builder()
                .type("request")
                .plugin("servlet")
                .className("FrameworkServlet")
                .methodName("service")
                .durationMs(50)
                .attribute("uri", "/api/users")
                .build();

        java.util.Map<String, Object> map = event.toMap();
        assertEquals("request", map.get("type"));
        assertEquals("servlet", map.get("plugin"));
        assertEquals(50L, map.get("durationMs"));
        assertNotNull(map.get("attributes"));
    }
}