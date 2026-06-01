package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * SPI interface for weaver-girl plugins.
 *
 * <p>Implementations are discovered via Java {@link java.util.ServiceLoader}
 * (declared in {@code META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin}).
 * Plugin developers implement this interface and package it in their JAR.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>The framework manages the following lifecycle for each plugin:</p>
 * <ol>
 *   <li><strong>Discovery</strong> &mdash; the plugin class is loaded via ServiceLoader</li>
 *   <li><strong>Initialization</strong> &mdash; {@link #init(PluginContext)} is called once at
 *       startup, before interceptors are registered. Use this to read configuration,
 *       establish connections, or allocate resources.</li>
 *   <li><strong>Registration</strong> &mdash; {@link #registerInterceptors(InterceptorRegistry)} is
 *       called once after initialization. This is where the plugin registers its interceptor
 *       definitions with the framework.</li>
 *   <li><strong>Runtime</strong> &mdash; the plugin's interceptors are invoked as matched methods
 *       are called in the target application. The plugin itself has no active role during
 *       this phase.</li>
 *   <li><strong>Destroy</strong> &mdash; {@link #destroy()} is called once at agent shutdown.
 *       Use this to release resources, flush buffers, or close connections.</li>
 * </ol>
 *
 * <h3>Default methods</h3>
 * <p>{@link #init(PluginContext)} and {@link #destroy()} have default no-op implementations.
 * Only {@link #name()} and {@link #registerInterceptors(InterceptorRegistry)} must be
 * implemented. For convenience, consider extending {@link AbstractPlugin} which provides
 * a fluent builder API for interceptor registration.</p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * public class TimingPlugin implements WeaverPlugin {
 *     &#64;Override
 *     public String name() { return "timing-plugin"; }
 *
 *     &#64;Override
 *     public void init(PluginContext context) {
 *         String threshold = context.getConfig("timing.thresholdMs", "100");
 *         // store threshold for later use...
 *     }
 *
 *     &#64;Override
 *     public void registerInterceptors(InterceptorRegistry registry) {
 *         registry.register(new InterceptorDefinition(
 *             "timing-interceptor",
 *             new Pointcut(ClassMatcher.byNamePattern("com\\.example\\..*"), MethodMatcher.any()),
 *             new TimingInterceptor()
 *         ));
 *     }
 *
 *     &#64;Override
 *     public void destroy() {
 *         // flush metrics, close resources, etc.
 *     }
 * }</pre>
 *
 * @see AbstractPlugin
 * @see PluginContext
 * @see InterceptorRegistry
 * @since 1.0.0
 */
public interface WeaverPlugin {

    /**
     * Returns the unique name of this plugin.
     *
     * <p>The name is used for logging, configuration scoping, and identification.
     * It should be a stable, human-readable identifier (e.g., {@code "timing-plugin"}).</p>
     *
     * @return the plugin name, never null
     */
    String name();

    /**
     * Initialize the plugin with access to the plugin context.
     *
     * <p>Called once during agent startup, before {@link #registerInterceptors(InterceptorRegistry)}.
     * Use this method to perform initialization such as loading configuration, establishing
     * connections, or allocating resources.</p>
     *
     * <p>Default implementation does nothing.</p>
     *
     * @param context the plugin context providing access to agent services and configuration
     */
    default void init(PluginContext context) {
        // no-op by default
    }

    /**
     * Register interceptor definitions with the registry.
     *
     * <p>Called once during agent startup, after {@link #init(PluginContext)}. This is the
     * only required method (besides {@link #name()}). All interceptor definitions that
     * the plugin wishes to activate must be registered here.</p>
     *
     * @param registry the interceptor registry to register definitions with
     */
    void registerInterceptors(InterceptorRegistry registry);

    /**
     * Destroy the plugin and release resources.
     *
     * <p>Called once during agent shutdown. Use this method to perform cleanup such as
     * closing connections, flushing buffers, or releasing allocated resources.</p>
     *
     * <p>Default implementation does nothing.</p>
     */
    default void destroy() {
        // no-op by default
    }
}
