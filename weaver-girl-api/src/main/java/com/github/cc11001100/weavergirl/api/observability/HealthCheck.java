package com.github.cc11001100.weavergirl.api.observability;

/**
 * A plugin-contributed health check for one component.
 *
 * <p>Register through {@code PluginContext.registerHealthCheck(...)} so the check is withdrawn when
 * the plugin is unloaded; registering on {@link HealthChecks} directly is possible but then the
 * plugin has to unregister it itself.
 *
 * <p>The check runs on whichever thread asks for health — usually a probe handler or a Prometheus
 * scrape. Keep it fast and non-blocking (a cached flag, a pool statistic, a cheap ping); a check that
 * throws is reported as {@code DOWN} and does not affect any other component.
 *
 * <p>Example:
 *
 * <pre>
 * context.registerHealthCheck(new HealthCheck() {
 *   &#64;Override
 *   public String name() {
 *     return "orders-db";
 *   }
 *
 *   &#64;Override
 *   public HealthCheckResult check() {
 *     return pool.isHealthy()
 *         ? HealthCheckResult.up()
 *         : HealthCheckResult.down("connection pool exhausted");
 *   }
 * });
 * </pre>
 *
 * @since 1.0.0
 */
public interface HealthCheck {

  /**
   * Component name, unique among registered checks. The first check registered with a name wins;
   * later duplicates are kept registered but never evaluated.
   *
   * @return a short, stable component name such as {@code "orders-db"}
   */
  String name();

  /**
   * Evaluate the component now.
   *
   * @return the result; null is reported as {@code DOWN} with an explanatory detail
   */
  HealthCheckResult check();
}
