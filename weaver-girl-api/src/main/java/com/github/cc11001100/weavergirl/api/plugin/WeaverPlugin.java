package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * SPI interface for weaver-girl plugins.
 *
 * <p>Implementations are discovered via Java {@link java.util.ServiceLoader} (declared in {@code
 * META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin}). Plugin developers
 * implement this interface and package it in their JAR.
 *
 * <h3>Lifecycle</h3>
 *
 * <p>The framework manages the following lifecycle for each plugin:
 *
 * <ol>
 *   <li><strong>Discovery</strong> &mdash; the plugin class is loaded via ServiceLoader
 *   <li><strong>Initialization</strong> &mdash; {@link #init(PluginContext)} is called once at
 *       startup, before interceptors are registered. Use this to read configuration, establish
 *       connections, or allocate resources.
 *   <li><strong>Registration</strong> &mdash; {@link #registerInterceptors(InterceptorRegistry)} is
 *       called once after initialization. This is where the plugin registers its interceptor
 *       definitions with the framework.
 *   <li><strong>Runtime</strong> &mdash; the plugin's interceptors are invoked as matched methods
 *       are called in the target application. The plugin itself has no active role during this
 *       phase.
 *   <li><strong>Destroy</strong> &mdash; {@link #destroy()} is called once at agent shutdown. Use
 *       this to release resources, flush buffers, or close connections.
 * </ol>
 *
 * <h3>Default methods</h3>
 *
 * <p>{@link #name()} is the only method that must be implemented. {@link
 * #registerInterceptors(InterceptorRegistry)} is a no-op by default, so a capability plugin — one
 * that starts a server, watches a file or publishes events from {@link #init(PluginContext)} — does
 * not have to write an empty method. For convenience, consider extending {@link AbstractPlugin},
 * which provides a fluent builder API for interceptor registration.
 *
 * <h3>Usage example</h3>
 *
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
   * Version of this plugin contract.
   *
   * <p>Bumped only when a change breaks plugins compiled against an older {@code weaver-girl-api}. A
   * plugin declares the contract it was built for with {@code plugin.apiVersion} in {@code
   * META-INF/weaver-girl-plugin.properties}, and the loader refuses to activate it when that version
   * is newer than this one — so a plugin never runs against an agent that lacks the API it calls.
   *
   * @since 1.0.0
   */
  String API_VERSION = "1.0.0";

  /**
   * Returns the unique name of this plugin.
   *
   * <p>The name is used for logging, configuration scoping, and identification. It should be a
   * stable, human-readable identifier (e.g., {@code "timing-plugin"}).
   *
   * @return the plugin name, never null
   */
  String name();

  /**
   * Initialize the plugin with access to the plugin context.
   *
   * <p>Called once during agent startup, before {@link #registerInterceptors(InterceptorRegistry)}.
   * Use this method to perform initialization such as loading configuration, establishing
   * connections, or allocating resources.
   *
   * <p>Default implementation does nothing.
   *
   * @param context the plugin context providing access to agent services and configuration
   */
  default void init(PluginContext context) {
    // no-op by default
  }

  /**
   * Register interceptor definitions with the registry.
   *
   * <p>Called once during agent startup, after {@link #init(PluginContext)}. All interceptor
   * definitions that the plugin wishes to activate must be registered here.
   *
   * <p>Default implementation registers nothing. A capability plugin — one whose work happens in
   * {@link #init(PluginContext)}, such as an endpoint or a watcher — overrides this only when it
   * also weaves something.
   *
   * @param registry the interceptor registry to register definitions with
   */
  default void registerInterceptors(InterceptorRegistry registry) {
    // no-op by default: capability plugins may not weave at all
  }

  /**
   * Declare the names of plugins that must be loaded before this plugin. The framework will ensure
   * all declared dependencies are initialized before this plugin's init() is called.
   *
   * <p>Default implementation returns an empty array (no dependencies).
   *
   * @return the names of plugins this plugin depends on
   */
  default String[] depends() {
    return new String[0];
  }

  /**
   * Plugins this one cannot run without. Unlike {@link #depends()}, which only orders initialization and
   * tolerates a dependency that was never loaded, a plugin is not activated at all when something it requires
   * is missing: starting half set up leaves it intercepting calls it cannot handle.
   *
   * <p>Default implementation returns an empty array, so a plugin that declares nothing behaves exactly as
   * before.
   *
   * @return the names of plugins this one refuses to load without
   * @since 1.0.0
   */
  default String[] requires() {
    return new String[0];
  }

  /**
   * Destroy the plugin and release resources.
   *
   * <p>Called once during agent shutdown. Use this method to perform cleanup such as closing
   * connections, flushing buffers, or releasing allocated resources.
   *
   * <p>Default implementation does nothing.
   */
  default void destroy() {
    // no-op by default
  }

  /**
   * Check whether this plugin should be enabled at runtime.
   *
   * <p>Called after {@link #init(PluginContext)} but before {@link
   * #registerInterceptors(InterceptorRegistry)}. If this method returns {@code false}, the plugin's
   * interceptors will NOT be registered, effectively disabling the plugin at runtime.
   *
   * <p>This allows plugins to perform conditional activation based on:
   *
   * <ul>
   *   <li>Configuration values (e.g., {@code context.getConfig("enabled", "true")})
   *   <li>Environment conditions (e.g., presence of a specific system property)
   *   <li>Feature flags or runtime state
   * </ul>
   *
   * <p>Default implementation always returns {@code true} (plugin is enabled).
   *
   * @param context the plugin context for checking configuration
   * @return {@code true} if the plugin should register its interceptors
   * @since 1.0.0
   */
  default boolean isEnabled(PluginContext context) {
    return true;
  }

  /**
   * The class whose presence means the library this plugin instruments is on the classpath, or null when the
   * plugin does not target a third-party library.
   *
   * <p>The loader checks this before registering interceptors and leaves the plugin inactive when the class
   * cannot be seen, so a plugin never weaves against a library the application does not use. The check looks
   * for the class file without loading it: loading it would define the class before the transformer is
   * installed, and the interceptor registered for it would never be woven in.
   *
   * <p>A plugin that instruments more than one library overrides {@link #instrumentedLibraries()} instead, and
   * this method is ignored. Default implementation returns null, so a plugin that declares nothing is
   * activated exactly as before.
   *
   * @return a fully-qualified class name, for example {@code "io.grpc.Server"}, or null
   * @since 1.0.0
   */
  default String instrumentedLibrary() {
    return null;
  }

  /**
   * The classes that stand for the libraries this plugin instruments, when it instruments more than one.
   *
   * <p>The loader activates the plugin when any one of them can be seen, and leaves it inactive only when
   * none can. A plugin that covers two clients of the same kind — a Redis plugin that weaves both Jedis and
   * Lettuce — would otherwise be switched off for an application that uses only the second. Which of the two
   * it actually registers interceptors for is the plugin's own decision, made from {@link
   * PluginContext#classPresent}; this list only decides whether the plugin runs at all.
   *
   * <p>Default implementation wraps {@link #instrumentedLibrary()}, so a plugin that names a single library
   * declares nothing here.
   *
   * @return fully-qualified class names, none of them null; empty when the plugin targets no library
   * @since 1.0.0
   */
  default String[] instrumentedLibraries() {
    String library = instrumentedLibrary();
    return library == null ? new String[0] : new String[] {library};
  }
}
