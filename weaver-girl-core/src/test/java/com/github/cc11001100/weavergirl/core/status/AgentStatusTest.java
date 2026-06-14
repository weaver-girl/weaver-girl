// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/status/AgentStatusTest.java
package com.github.cc11001100.weavergirl.core.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentStatusTest {

    private AgentStatus status;

    @BeforeEach
    void setUp() {
        status = AgentStatus.getInstance();
        status.reset();
    }

    @Test
    void getInstanceReturnsSameInstance() {
        AgentStatus a = AgentStatus.getInstance();
        AgentStatus b = AgentStatus.getInstance();
        assertSame(a, b);
    }

    @Test
    void incrementTransformationCount() {
        assertEquals(0, status.getTransformationCount());
        assertEquals(1, status.incrementTransformationCount());
        assertEquals(2, status.incrementTransformationCount());
        assertEquals(2, status.getTransformationCount());
    }

    @Test
    void incrementTransformationErrorCount() {
        assertEquals(0, status.getTransformationErrorCount());
        assertEquals(1, status.incrementTransformationErrorCount());
        assertEquals(2, status.incrementTransformationErrorCount());
        assertEquals(2, status.getTransformationErrorCount());
    }

    @Test
    void incrementInterceptorInvocationCount() {
        assertEquals(0, status.getInterceptorInvocationCount());
        assertEquals(1, status.incrementInterceptorInvocationCount());
        assertEquals(2, status.incrementInterceptorInvocationCount());
        assertEquals(2, status.getInterceptorInvocationCount());
    }

    @Test
    void incrementInterceptorErrorCount() {
        assertEquals(0, status.getInterceptorErrorCount());
        assertEquals(1, status.incrementInterceptorErrorCount());
        assertEquals(2, status.incrementInterceptorErrorCount());
        assertEquals(2, status.getInterceptorErrorCount());
    }

    @Test
    void setActivePluginCountAndGetActivePluginCount() {
        assertEquals(0, status.getActivePluginCount());
        status.setActivePluginCount(5);
        assertEquals(5, status.getActivePluginCount());
    }

    @Test
    void setRegisteredInterceptorCountAndGetRegisteredInterceptorCount() {
        assertEquals(0, status.getRegisteredInterceptorCount());
        status.setRegisteredInterceptorCount(3);
        assertEquals(3, status.getRegisteredInterceptorCount());
    }

    @Test
    void putCustomMetricAndGetCustomMetrics() {
        assertTrue(status.getCustomMetrics().isEmpty());
        status.putCustomMetric("key1", "value1");
        status.putCustomMetric("key2", "value2");
        Map<String, String> metrics = status.getCustomMetrics();
        assertEquals("value1", metrics.get("key1"));
        assertEquals("value2", metrics.get("key2"));
        assertEquals(2, metrics.size());
    }

    @Test
    void getCustomMetricsIsUnmodifiable() {
        status.putCustomMetric("k", "v");
        Map<String, String> metrics = status.getCustomMetrics();
        assertThrows(UnsupportedOperationException.class, () -> metrics.put("x", "y"));
    }

    @Test
    void getUptimeSecondsIsNonNegative() {
        // Uptime should be >= 0 immediately after reset
        assertTrue(status.getUptimeSeconds() >= 0);
    }

    @Test
    void getReportContainsExpectedFields() {
        status.incrementTransformationCount();
        status.incrementTransformationErrorCount();
        status.incrementInterceptorInvocationCount();
        status.incrementInterceptorErrorCount();
        status.setActivePluginCount(2);
        status.setRegisteredInterceptorCount(3);
        status.putCustomMetric("testMetric", "testValue");

        String report = status.getReport();

        assertTrue(report.contains("Weaver-Girl Agent Status"));
        assertTrue(report.contains("Uptime:"));
        assertTrue(report.contains("Active Plugins: 2"));
        assertTrue(report.contains("Registered Interceptors: 3"));
        assertTrue(report.contains("Transformations: 1"));
        assertTrue(report.contains("errors: 1"));
        assertTrue(report.contains("Interceptor Invocations: 1"));
        assertTrue(report.contains("testMetric: testValue"));
    }

    @Test
    void resetZeroesAllCounters() {
        status.incrementTransformationCount();
        status.incrementTransformationErrorCount();
        status.incrementInterceptorInvocationCount();
        status.incrementInterceptorErrorCount();
        status.setActivePluginCount(10);
        status.setRegisteredInterceptorCount(20);
        status.putCustomMetric("k", "v");

        status.reset();

        assertEquals(0, status.getTransformationCount());
        assertEquals(0, status.getTransformationErrorCount());
        assertEquals(0, status.getInterceptorInvocationCount());
        assertEquals(0, status.getInterceptorErrorCount());
        assertEquals(0, status.getActivePluginCount());
        assertEquals(0, status.getRegisteredInterceptorCount());
        assertTrue(status.getCustomMetrics().isEmpty());
        assertTrue(status.getUptimeSeconds() >= 0);
    }
}
