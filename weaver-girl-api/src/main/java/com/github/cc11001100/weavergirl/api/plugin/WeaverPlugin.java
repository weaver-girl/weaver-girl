package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * SPI interface for weaver-girl plugins.
 * Implementations are discovered via Java ServiceLoader (META-INF/services).
 *
 * <p>Plugin developers implement this interface and declare it in
 * {@code META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin}.</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #init(PluginContext)} — called once at startup, before interceptors are registered</li>
 *   <li>{@link #registerInterceptors(InterceptorRegistry)} — called once to register interceptors</li>
 *   <li>{@link #destroy()} — called once at shutdown for cleanup</li>
 * </ol>
 */
public interface WeaverPlugin {

    /**
     * Unique plugin name.
     */
    String name();

    /**
     * Initialize the plugin with access to the plugin context.
     * Called once during agent startup, before {@link #registerInterceptors(InterceptorRegistry)}.
     *
     * <p>Default implementation does nothing. Override to perform initialization
     * such as loading configuration, establishing connections, etc.</p>
     *
     * @param context the plugin context providing access to agent services
     */
    default void init(PluginContext context) {
        // no-op by default
    }

    /**
     * Register interceptor definitions with the registry.
     * Called once during agent startup, after {@link #init(PluginContext)}.
     */
    void registerInterceptors(InterceptorRegistry registry);

    /**
     * Destroy the plugin and release resources.
     * Called once during agent shutdown.
     *
     * <p>Default implementation does nothing. Override to perform cleanup
     * such as closing connections, flushing buffers, etc.</p>
     */
    default void destroy() {
        // no-op by default
    }
}
