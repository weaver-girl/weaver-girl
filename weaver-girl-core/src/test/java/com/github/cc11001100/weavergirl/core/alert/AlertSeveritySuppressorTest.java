package com.github.cc11001100.weavergirl.core.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlertSeveritySuppressorTest {

    private AlertSeveritySuppressor suppressor;

    @BeforeEach
    void setUp() {
        suppressor = new AlertSeveritySuppressor();
    }

    @Test
    void shouldSuppress_noActiveAlerts_returnsFalse() {
        assertFalse(suppressor.shouldSuppress("cpu", "warning"));
    }

    @Test
    void shouldSuppress_criticalSuppressesWarning() {
        suppressor.recordActive("cpu", "critical");
        assertTrue(suppressor.shouldSuppress("cpu", "warning"));
    }

    @Test
    void shouldSuppress_criticalDoesNotSuppressCritical() {
        suppressor.recordActive("cpu", "critical");
        assertFalse(suppressor.shouldSuppress("cpu", "critical"));
    }

    @Test
    void shouldSuppress_warningDoesNotSuppressCritical() {
        suppressor.recordActive("cpu", "warning");
        assertFalse(suppressor.shouldSuppress("cpu", "critical"));
    }

    @Test
    void shouldSuppress_warningSuppressesInfo() {
        suppressor.recordActive("cpu", "warning");
        assertTrue(suppressor.shouldSuppress("cpu", "info"));
    }

    @Test
    void shouldSuppress_differentMetricsAreIndependent() {
        suppressor.recordActive("cpu", "critical");
        assertFalse(suppressor.shouldSuppress("memory", "warning"));
    }

    @Test
    void clearActive_removesSeverityTracking() {
        suppressor.recordActive("cpu", "critical");
        suppressor.clearActive("cpu");
        assertFalse(suppressor.shouldSuppress("cpu", "warning"));
    }
}
