package com.github.cc11001100.weavergirl.api.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeWindowAggregatorTest {

    private TimeWindowAggregator agg;

    @BeforeEach
    void setUp() {
        agg = new TimeWindowAggregator("test", 60000);
        MetricRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        MetricRegistry.clear();
    }

    @Test
    void snapshot_emptyWindow_returnsNonNullPercentiles() {
        MetricSnapshot snap = agg.snapshot();
        assertNotNull(snap.getPercentiles());
        assertTrue(snap.getPercentiles().isEmpty());
    }

    @Test
    void snapshot_emptyWindow_countIsZero() {
        MetricSnapshot snap = agg.snapshot();
        assertEquals(0, snap.getCount());
    }

    @Test
    void recordAndSnapshot_includesRecordedValue() {
        agg.record(42.0);
        MetricSnapshot snap = agg.snapshot();
        assertEquals(1, snap.getCount());
        assertEquals(42.0, snap.getSum(), 0.01);
        assertEquals(42.0, snap.getMin(), 0.01);
        assertEquals(42.0, snap.getMax(), 0.01);
    }

    @Test
    void getPercentile_emptySnapshot_returnsZero() {
        MetricSnapshot snap = agg.snapshot();
        assertEquals(0.0, snap.getPercentile(0.99), 0.01);
    }

    @Test
    void getOrCreateStrict_sameWindow_returnsExisting() {
        TimeWindowAggregator a1 = MetricRegistry.getOrCreate("test-metric", 60000);
        TimeWindowAggregator a2 = MetricRegistry.getOrCreateStrict("test-metric", 60000);
        assertSame(a1, a2);
    }

    @Test
    void getOrCreateStrict_differentWindow_returnsExistingAndWarns() {
        TimeWindowAggregator a1 = MetricRegistry.getOrCreate("test-metric", 60000);
        TimeWindowAggregator a2 = MetricRegistry.getOrCreateStrict("test-metric", 300000);
        assertSame(a1, a2);
        assertEquals(60000, a2.getWindowMs());
    }
}