package com.github.cc11001100.weavergirl.core.metrics;

import com.github.cc11001100.weavergirl.api.metrics.MetricLabels;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricLabelsTest {

    @AfterEach
    void tearDown() {
        MetricRegistry.clear();
    }

    @Test
    void record_withLabels_shouldCreateLabeledMetric() {
        Map<String, String> labels = MetricLabels.of("method", "GET").build();
        MetricRegistry.record("http_requests", labels, 1.0);

        MetricSnapshot snap = MetricRegistry.snapshot("http_requests", labels);
        assertNotNull(snap);
        assertEquals(1, snap.getCount());
        assertEquals(1.0, snap.getSum(), 0.001);
    }

    @Test
    void record_differentLabels_shouldCreateSeparateMetrics() {
        Map<String, String> getLabels = MetricLabels.of("method", "GET").build();
        Map<String, String> postLabels = MetricLabels.of("method", "POST").build();

        MetricRegistry.record("http_requests", getLabels, 1.0);
        MetricRegistry.record("http_requests", getLabels, 2.0);
        MetricRegistry.record("http_requests", postLabels, 5.0);

        MetricSnapshot getSsnap = MetricRegistry.snapshot("http_requests", getLabels);
        MetricSnapshot postSnap = MetricRegistry.snapshot("http_requests", postLabels);

        assertNotNull(getSsnap);
        assertNotNull(postSnap);
        assertEquals(2, getSsnap.getCount());
        assertEquals(3.0, getSsnap.getSum(), 0.001);
        assertEquals(1, postSnap.getCount());
        assertEquals(5.0, postSnap.getSum(), 0.001);
    }

    @Test
    void getMetricsByName_shouldReturnAllVariants() {
        Map<String, String> getLabels = MetricLabels.of("method", "GET").build();
        Map<String, String> postLabels = MetricLabels.of("method", "POST").build();

        MetricRegistry.record("http_requests", getLabels, 1.0);
        MetricRegistry.record("http_requests", postLabels, 1.0);

        List<String> names = MetricRegistry.getMetricsByName("http_requests");

        assertEquals(2, names.size());
        assertTrue(names.stream().anyMatch(k -> k.contains("method=GET")));
        assertTrue(names.stream().anyMatch(k -> k.contains("method=POST")));
    }

    @Test
    void metricLabels_builder_shouldCreateCorrectMap() {
        Map<String, String> single = MetricLabels.of("key", "val").build();
        assertEquals(1, single.size());
        assertEquals("val", single.get("key"));

        Map<String, String> pair = MetricLabels.of("k1", "v1", "k2", "v2").build();
        assertEquals(2, pair.size());
        assertEquals("v1", pair.get("k1"));
        assertEquals("v2", pair.get("k2"));

        Map<String, String> empty = MetricLabels.empty();
        assertTrue(empty.isEmpty());
    }

    @Test
    void snapshot_includesLabels() {
        Map<String, String> labels = MetricLabels.of("method", "GET", "path", "/api/users").build();
        MetricRegistry.record("http_requests", labels, 1.0);

        MetricSnapshot snap = MetricRegistry.snapshot("http_requests", labels);
        assertNotNull(snap);
        assertEquals(labels, snap.getLabels());
    }

    @Test
    void backwardCompat_recordWithoutLabels_stillWorks() {
        MetricRegistry.record("plain_metric", 42.0);

        MetricSnapshot snap = MetricRegistry.snapshot("plain_metric");
        assertNotNull(snap);
        assertEquals(1, snap.getCount());
        assertEquals(42.0, snap.getSum(), 0.001);
        assertTrue(snap.getLabels().isEmpty());
    }
}
