// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptorHolder.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Global holder for the InterceptorRegistry instance.
 * Needed because ByteBuddy Advice classes are static and cannot
 * access instance fields — they need a global reference.
 */
public class InterceptorHolder {

    private static volatile InterceptorRegistry registry;

    public static void setRegistry(InterceptorRegistry registry) {
        InterceptorHolder.registry = registry;
    }

    public static InterceptorRegistry getRegistry() {
        return registry;
    }
}
