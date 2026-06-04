package com.github.cc11001100.weavergirl.core.exporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpanExporterTest {

    private SpanExporter exporter;
    private SpanFormatter.InMemorySpanFormatter inMemory;

    @BeforeEach
    void setUp() {
        exporter = new SpanExporter(10, 1000, 1000);
        inMemory = new SpanFormatter.InMemorySpanFormatter(1000);
        exporter.addFormatter(inMemory);
    }

    @AfterEach
    void tearDown() {
        if (exporter.isRunning()) {
            exporter.stop();
        }
    }

    private SpanData createSpan(String traceId, String spanId, String opName, long durationMs) {
        return SpanData.builder()
                .traceId(traceId)
                .spanId(spanId)
                .operationName(opName)
                .startTimeMs(System.currentTimeMillis())
                .durationMs(durationMs)
                .build();
    }

    @Test
    void submit_shouldAcceptSpan() {
        SpanData span = createSpan("t1", "s1", "test-op", 100);
        assertTrue(exporter.submit(span));
        assertEquals(1, exporter.getPendingCount());
    }

    @Test
    void submit_shouldRejectNull() {
        assertFalse(exporter.submit(null));
        assertEquals(0, exporter.getPendingCount());
    }

    @Test
    void flush_shouldExportPendingSpans() {
        exporter.submit(createSpan("t1", "s1", "op1", 10));
        exporter.submit(createSpan("t1", "s2", "op2", 20));

        int flushed = exporter.flush();
        assertEquals(2, flushed);
        assertEquals(2, exporter.getExportedCount());
        assertEquals(2, inMemory.size());
    }

    @Test
    void start_shouldTriggerPeriodicExport() throws InterruptedException {
        exporter.submit(createSpan("t1", "s1", "op1", 10));
        exporter.start();

        // Wait for at least one export cycle
        Thread.sleep(1500);

        assertEquals(1, exporter.getExportedCount());
        assertEquals(1, inMemory.size());
    }

    @Test
    void stop_shouldFlushRemainingSpans() {
        exporter.start();
        exporter.submit(createSpan("t1", "s1", "op1", 10));

        exporter.stop();

        assertEquals(1, exporter.getExportedCount());
        assertEquals(1, inMemory.size());
    }

    @Test
    void bufferFull_shouldDropSpans() {
        SpanExporter smallExporter = new SpanExporter(10, 60000, 2);
        smallExporter.addFormatter(inMemory);

        assertTrue(smallExporter.submit(createSpan("t1", "s1", "op1", 10)));
        assertTrue(smallExporter.submit(createSpan("t1", "s2", "op2", 10)));
        assertFalse(smallExporter.submit(createSpan("t1", "s3", "op3", 10)));

        assertEquals(1, smallExporter.getDroppedCount());
    }

    @Test
    void statistics_shouldTrackExportMetrics() {
        exporter.submit(createSpan("t1", "s1", "op1", 10));
        exporter.submit(createSpan("t1", "s2", "op2", 20));
        exporter.flush();

        assertEquals(2, exporter.getExportedCount());
        assertEquals(1, exporter.getExportBatchCount());
        assertEquals(0, exporter.getDroppedCount());
        assertEquals(0, exporter.getFailedExportCount());
    }
}
