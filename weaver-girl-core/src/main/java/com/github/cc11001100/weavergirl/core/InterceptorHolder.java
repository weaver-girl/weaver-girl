// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptorHolder.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;

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

    private static volatile InterceptorRegistry registry;

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
}
