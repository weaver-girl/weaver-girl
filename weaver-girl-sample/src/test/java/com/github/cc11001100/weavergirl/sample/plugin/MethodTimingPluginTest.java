package com.github.cc11001100.weavergirl.sample.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the MethodTimingPlugin reference plugin.
 */
class MethodTimingPluginTest {

    @Test
    void pluginNameIsCorrect() {
        MethodTimingPlugin plugin = new MethodTimingPlugin();
        assertEquals("method-timing", plugin.name());
    }

    @Test
    void timingInterceptorMeasuresExecutionTime() {
        Interceptor interceptor = MethodTimingPlugin.createTimingInterceptor();

        // Create a mock MethodInvocation
        MethodInvocation invocation = new MethodInvocation(
            String.class, "toString", null, null, new Object[0]);

        // before should set start time
        interceptor.before(invocation);

        // Simulate some work
        try { Thread.sleep(10); } catch (InterruptedException e) {}

        // after should log timing (we just verify it doesn't throw)
        assertDoesNotThrow(() -> interceptor.after(invocation));
    }

    @Test
    void timingInterceptorCleansUpOnException() {
        Interceptor interceptor = MethodTimingPlugin.createTimingInterceptor();

        MethodInvocation invocation = new MethodInvocation(
            String.class, "process", null, null, new Object[0]);

        interceptor.before(invocation);
        assertDoesNotThrow(() -> interceptor.onException(invocation));
    }
}