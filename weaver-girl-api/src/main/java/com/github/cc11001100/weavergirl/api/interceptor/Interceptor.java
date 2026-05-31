package com.github.cc11001100.weavergirl.api.interceptor;

/**
 * Core interceptor interface for method-level around-advice.
 * Implementations provide before/after/exception hooks.
 *
 * <p>All methods have default no-op implementations so implementors
 * only need to override the hooks they care about.</p>
 *
 * <p><strong>Safety contract:</strong> Implementations MUST NOT throw
 * exceptions that escape these methods. If an exception occurs, catch
 * it internally. The framework will also catch exceptions as a safety net,
 * but implementations should handle their own errors gracefully.</p>
 */
public interface Interceptor {

    /**
     * Called before the target method executes.
     * Use invocation.skipMethod() to skip the original method execution.
     */
    default void before(MethodInvocation invocation) {
    }

    /**
     * Called after the target method executes successfully.
     */
    default void after(MethodInvocation invocation) {
    }

    /**
     * Called when the target method throws an exception.
     */
    default void onException(MethodInvocation invocation) {
    }
}
