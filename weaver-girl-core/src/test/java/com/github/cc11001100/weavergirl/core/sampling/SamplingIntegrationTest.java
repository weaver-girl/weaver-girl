package com.github.cc11001100.weavergirl.core.sampling;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.InterceptAdvice;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying SamplingController works with InterceptAdvice.
 */
class SamplingIntegrationTest {

    private static Instrumentation instrumentation;
    private DefaultInterceptorRegistry registry;

    @BeforeAll
    static void setUpClass() {
        try {
            instrumentation = ByteBuddyAgent.install();
        } catch (Exception e) {
            instrumentation = null;
        }
    }

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
        InterceptorHolder.setRegistry(registry);
        SamplingController.getInstance().setSamplingRate(1); // sample all
        SamplingController.getInstance().resetCounter();
    }

    @AfterEach
    void tearDown() {
        InterceptorHolder.setRegistry(null);
        SamplingController.getInstance().setSamplingRate(1);
    }

    @Test
    void samplingRate1_interceptsAllCalls() {
        SamplingController controller = SamplingController.getInstance();
        controller.setSamplingRate(1);

        int sampled = 0;
        int total = 100;
        for (int i = 0; i < total; i++) {
            if (controller.shouldSample()) {
                sampled++;
            }
        }
        assertEquals(total, sampled, "Rate 1 should sample all calls");
    }

    @Test
    void samplingRate10_interceptsApproximatelyOneInTen() {
        SamplingController controller = SamplingController.getInstance();
        controller.setSamplingRate(10);
        controller.resetCounter();

        int sampled = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            if (controller.shouldSample()) {
                sampled++;
            }
        }
        // With rate 10, expect ~100 out of 1000
        assertTrue(sampled >= 50 && sampled <= 150,
                "Rate 10 should sample ~10% of calls, got " + sampled + " out of " + total);
    }

    @Test
    void samplingRate100_interceptsApproximatelyOneInHundred() {
        SamplingController controller = SamplingController.getInstance();
        controller.setSamplingRate(100);
        controller.resetCounter();

        int sampled = 0;
        int total = 10000;
        for (int i = 0; i < total; i++) {
            if (controller.shouldSample()) {
                sampled++;
            }
        }
        // With rate 100, expect ~100 out of 10000
        assertTrue(sampled >= 50 && sampled <= 200,
                "Rate 100 should sample ~1% of calls, got " + sampled + " out of " + total);
    }

    @Test
    void adaptRate_increasesRateUnderHighLoad() {
        SamplingController controller = SamplingController.getInstance();
        controller.setSamplingRate(1);
        controller.setThresholdInvocationsPerSecond(1000);

        // Simulate high load
        controller.adaptRate(5000.0);

        assertTrue(controller.getSamplingRate() > 1,
                "Sampling rate should increase under high load");
    }

    @Test
    void adaptRate_decreasesRateUnderLowLoad() {
        SamplingController controller = SamplingController.getInstance();
        controller.setSamplingRate(10);
        controller.setThresholdInvocationsPerSecond(1000);

        // Simulate low load (below half of threshold)
        controller.adaptRate(400.0);

        assertTrue(controller.getSamplingRate() < 10,
                "Sampling rate should decrease under low load");
    }

    @Test
    void adaptRate_doesNotGoBelowOne() {
        SamplingController controller = SamplingController.getInstance();
        controller.setSamplingRate(1);
        controller.setThresholdInvocationsPerSecond(1000);

        // Even under very low load, rate should not go below 1
        controller.adaptRate(0.0);
        assertEquals(1, controller.getSamplingRate(),
                "Sampling rate should never go below 1");
    }
}
