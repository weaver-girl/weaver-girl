package com.github.cc11001100.weavergirl.core.tracing;

import com.github.cc11001100.weavergirl.api.tracing.CompositeSpanCompletionListener;
import com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompositeSpanCompletionListenerTest {

    private CompositeSpanCompletionListener composite;
    private List<String> calls;

    @BeforeEach
    void setUp() {
        composite = new CompositeSpanCompletionListener();
        calls = new ArrayList<>();
    }

    private SpanContext sampleSpan() {
        return SpanContext.builder().traceId("t1").spanId("s1").build();
    }

    @Test
    void onSpanComplete_notifiesAllListeners() {
        composite.addListener((span, dur) -> calls.add("a"));
        composite.addListener((span, dur) -> calls.add("b"));
        composite.onSpanComplete(sampleSpan(), 50);
        assertEquals(2, calls.size());
        assertTrue(calls.contains("a"));
        assertTrue(calls.contains("b"));
    }

    @Test
    void onSpanComplete_exceptionInOneListener_doesNotBlockOthers() {
        composite.addListener((span, dur) -> { throw new RuntimeException("boom"); });
        composite.addListener((span, dur) -> calls.add("survived"));
        composite.onSpanComplete(sampleSpan(), 50);
        assertEquals(1, calls.size());
        assertEquals("survived", calls.get(0));
    }

    @Test
    void addListener_null_ignored() {
        composite.addListener(null);
        assertEquals(0, composite.size());
    }

    @Test
    void removeListener_stopsNotifications() {
        SpanCompletionListener listener = (span, dur) -> calls.add("called");
        composite.addListener(listener);
        composite.removeListener(listener);
        composite.onSpanComplete(sampleSpan(), 50);
        assertTrue(calls.isEmpty());
    }

    @Test
    void setCompletionListener_replacesAllExisting() {
        Tracer.setCompletionListener((span, dur) -> calls.add("old"));
        Tracer.setCompletionListener((span, dur) -> calls.add("new"));
        Tracer.startSpan();
        Tracer.endSpan("test", "OK");
        assertTrue(calls.contains("new"));
        assertFalse(calls.contains("old"));
        // Cleanup
        Tracer.setCompletionListener(null);
    }
}
