package com.github.cc11001100.weavergirl.core.sampling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SamplingControllerTest {

    private SamplingController controller;

    @BeforeEach
    void setUp() {
        // Use getInstance() but reset state before each test
        controller = SamplingController.getInstance();
        controller.resetCounter();
        controller.setSamplingRate(1);
        controller.setMaxRate(100);
        controller.setThresholdInvocationsPerSecond(10000);
    }

    @Test
    void shouldSampleReturnsTrueWhenRateIs1() {
        controller.setSamplingRate(1);
        // Every call should return true when rate is 1
        for (int i = 0; i < 100; i++) {
            assertTrue(controller.shouldSample(), "shouldSample should return true when rate is 1");
        }
    }

    @Test
    void shouldSampleReturnsFalseForSomeCallsWhenRateGreaterThan1() {
        controller.setSamplingRate(10);
        int sampled = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            if (controller.shouldSample()) {
                sampled++;
            }
        }
        // With rate 10, roughly 1 in 10 should be sampled
        assertTrue(sampled < total, "Some calls should be skipped when rate > 1");
        assertTrue(sampled > 0, "At least some calls should be sampled when rate > 1");
        // Approximately total/rate sampled
        assertEquals(total / 10, sampled, "Sampled count should be approximately total/rate");
    }

    @Test
    void setSamplingRateClampsTo1Minimum() {
        controller.setSamplingRate(0);
        assertEquals(1, controller.getSamplingRate());

        controller.setSamplingRate(-5);
        assertEquals(1, controller.getSamplingRate());
    }

    @Test
    void setSamplingRateClampsToMaxRate() {
        controller.setMaxRate(50);
        controller.setSamplingRate(200);
        assertEquals(50, controller.getSamplingRate());
    }

    @Test
    void adaptRateIncreasesWhenOverThreshold() {
        controller.setSamplingRate(5);
        controller.setThresholdInvocationsPerSecond(1000);
        // Simulate 5000 inv/s which is above threshold
        controller.adaptRate(5000.0);
        assertEquals(6, controller.getSamplingRate());
    }

    @Test
    void adaptRateDecreasesWhenUnderHalfThreshold() {
        controller.setSamplingRate(10);
        controller.setThresholdInvocationsPerSecond(1000);
        // Simulate 200 inv/s which is below threshold/2 = 500
        controller.adaptRate(200.0);
        assertEquals(9, controller.getSamplingRate());
    }

    @Test
    void adaptRateDoesNotDecreaseBelow1() {
        controller.setSamplingRate(1);
        controller.setThresholdInvocationsPerSecond(1000);
        controller.adaptRate(100.0);
        assertEquals(1, controller.getSamplingRate());
    }

    @Test
    void adaptRateDoesNotIncreaseAboveMaxRate() {
        controller.setSamplingRate(100);
        controller.setMaxRate(100);
        controller.setThresholdInvocationsPerSecond(1000);
        controller.adaptRate(5000.0);
        assertEquals(100, controller.getSamplingRate());
    }

    @Test
    void getInvocationCountTracksCalls() {
        assertEquals(0, controller.getInvocationCount());
        controller.shouldSample();
        assertEquals(1, controller.getInvocationCount());
        controller.shouldSample();
        controller.shouldSample();
        assertEquals(3, controller.getInvocationCount());
    }

    @Test
    void resetCounterZeroesCount() {
        for (int i = 0; i < 50; i++) {
            controller.shouldSample();
        }
        assertTrue(controller.getInvocationCount() > 0);
        controller.resetCounter();
        assertEquals(0, controller.getInvocationCount());
    }
}
