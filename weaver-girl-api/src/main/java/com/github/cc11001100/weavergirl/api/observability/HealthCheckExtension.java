package com.github.cc11001100.weavergirl.api.observability;

/**
 * Interface for extensions that can report their own health status.
 *
 * <p>Extensions that maintain stateful resources (network connections, background threads, buffers,
 * etc.) should implement this so the agent can monitor their health without a bespoke {@link
 * HealthCheck} per component. {@link HealthChecks} accepts either: a {@code HealthCheck} is a
 * standalone probe, while a {@code HealthCheckExtension} is a capability an extension implements,
 * so the extension itself stays the single place that knows its own state.
 *
 * <p>The default implementation returns {@link HealthCheckResult#up()}, so an extension that has
 * nothing to report is registered with no extra code.
 *
 * <p>The check runs on the scrape or probe thread, so implementations must be fast and
 * non-blocking. Long-running or blocking operations (a ping, a connection test) should be performed
 * by a background task in the extension and cached here as a flag.
 *
 * <p>Register through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerHealthCheckExtension}
 * so the check is withdrawn when the plugin is unloaded.
 *
 * <pre>
 * public class KafkaSink implements SpanSink, HealthCheckExtension {
 *   private volatile boolean connected = true;
 *
 *   &#64;Override
 *   public String name() {
 *     return "kafka-sink";
 *   }
 *
 *   &#64;Override
 *   public HealthCheckResult checkHealth() {
 *     return connected
 *         ? HealthCheckResult.up()
 *         : HealthCheckResult.down("producer disconnected");
 *   }
 *   // onSpanEnd(...) omitted
 * }
 * </pre>
 *
 * @see HealthChecks
 * @since 1.2.0
 */
public interface HealthCheckExtension {

  /**
   * Stable name used as the component label of the health series, for logging and for the
   * performance counters.
   *
   * <p>The default is the implementation's fully-qualified class name, which is stable across
   * restarts and unique enough for an anonymous extension. A plugin registering through {@code
   * PluginContext} gets the plugin name prefixed automatically, so two plugins can both report a
   * component called {@code "backend"} without colliding.
   *
   * @return the component name, never null
   */
  default String name() {
    return getClass().getName();
  }

  /**
   * Check if this extension is healthy.
   *
   * <p>This is called by a health probe and during Prometheus scrapes. Extensions should only
   * perform quick checks here; long-running or blocking operations (e.g., a ping or connection
   * test) should be cached in the extension itself, not done here.
   *
   * @return the health status; null is reported as {@code DOWN} with an explanatory detail
   */
  default HealthCheckResult checkHealth() {
    return HealthCheckResult.up();
  }
}
