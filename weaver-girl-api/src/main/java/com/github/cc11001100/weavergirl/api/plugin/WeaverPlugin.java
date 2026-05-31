package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * SPI interface for weaver-girl plugins.
 * Implementations are discovered via Java ServiceLoader (META-INF/services).
 *
 * <p>Plugin developers implement this interface and declare it in
 * {@code META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin}.</p>
 */
public interface WeaverPlugin {

    /**
     * Unique plugin name.
     */
    String name();

    /**
     * Register interceptor definitions with the registry.
     * Called once during agent startup.
     */
    void registerInterceptors(InterceptorRegistry registry);
}
