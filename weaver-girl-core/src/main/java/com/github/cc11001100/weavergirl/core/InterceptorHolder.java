package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.circuit.InterceptorCircuitBreaker;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global holder for the InterceptorRegistry instance.
 * Needed because ByteBuddy Advice classes are static and cannot
 * access instance fields — they need a global reference.
 *
 * <p>Also provides static delegate methods for AgentStatus counters,
 * so that InterceptAdvice (which is inlined by ByteBuddy and cannot
 * reference classes not on the bootstrap classloader) can increment
 * status counters indirectly.</p>
 */
public class InterceptorHolder {

    private static final Logger LOG = LoggerFactory.getLogger(InterceptorHolder.class);

    private static volatile InterceptorRegistry registry;
    private static final InterceptorCircuitBreaker circuitBreaker = new InterceptorCircuitBreaker();

    public static void logInterceptorError(String interceptorName, String phase, Throwable e) {
        LOG.warn("Interceptor '{}' failed in {}: {}", interceptorName, phase, e.getMessage());
    }

    public static void setRegistry(InterceptorRegistry registry) {
        InterceptorHolder.registry = registry;
    }

    public static InterceptorRegistry getRegistry() {
        return registry;
    }

    // --- AgentStatus delegates ---

    public static void incrementInterceptorInvocationCount() {
        AgentStatus.getInstance().incrementInterceptorInvocationCount();
    }

    public static void incrementInterceptorErrorCount() {
        AgentStatus.getInstance().incrementInterceptorErrorCount();
    }

    // --- Circuit breaker delegates ---

    public static boolean shouldInvoke(String interceptorName) {
        return circuitBreaker.shouldInvoke(interceptorName);
    }

    public static void recordInterceptorSuccess(String interceptorName) {
        circuitBreaker.recordSuccess(interceptorName);
    }

    public static void recordInterceptorFailure(String interceptorName) {
        circuitBreaker.recordFailure(interceptorName);
    }
}
