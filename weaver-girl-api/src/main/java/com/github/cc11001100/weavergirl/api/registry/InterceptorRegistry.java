package com.github.cc11001100.weavergirl.api.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;

import java.util.List;

/**
 * Registry for interceptor definitions.
 *
 * <p>API module defines the interface; Core module provides the implementation.
 * This separation allows plugin developers to code against the interface
 * without depending on the core engine.</p>
 */
public interface InterceptorRegistry {

    /**
     * Register an interceptor definition.
     */
    void register(InterceptorDefinition definition);

    /**
     * Unregister an interceptor definition by name.
     *
     * @param name the name of the interceptor definition to remove
     * @return true if a definition was removed, false if not found
     */
    boolean unregister(String name);

    /**
     * Get all interceptor definitions that match the given class name.
     */
    List<InterceptorDefinition> getInterceptorsForClass(String className);

    /**
     * Get all registered definitions.
     */
    List<InterceptorDefinition> getAllDefinitions();
}
