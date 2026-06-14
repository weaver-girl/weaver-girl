package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.circuit.InterceptorCircuitBreaker;
import com.github.cc11001100.weavergirl.core.management.AgentMonitor;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
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

    // --- Global interception switch delegates ---

    /**
     * @return true if global interception is enabled. Consulted by {@code InterceptAdvice}
     *     on every enter/exit as a single volatile read.
     */
    public static boolean isInterceptionEnabled() {
        return GlobalInterceptionSwitch.isEnabled();
    }

    /** Process-wide kill-switch toggle. */
    public static void setInterceptionEnabled(boolean enabledFlag, String source) {
        GlobalInterceptionSwitch.setEnabled(enabledFlag, source);
    }

    // --- AgentStatus delegates ---

    public static void incrementInterceptorInvocationCount() {
        AgentStatus.getInstance().incrementInterceptorInvocationCount();
        AgentMonitor.getInstance().incrementInterceptCount();
    }

    public static void incrementInterceptorErrorCount() {
        AgentStatus.getInstance().incrementInterceptorErrorCount();
    }

    public static void recordInterceptTime(long nanos) {
        AgentMonitor.getInstance().addInterceptTime(nanos);
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
