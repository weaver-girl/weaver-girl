package com.github.cc11001100.weavergirl.plugins.sparkjava;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spark Java instrumentation plugin. Times a matched route running.
 *
 * <p>{@code Routes} is the route table every request is resolved against and {@code find} is the
 * method that matches a request to its route, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SparkJavaPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String ROUTES = "spark.route.Routes";

  @Override
  public String name() {
    return "sparkjava";
  }

  @Override
  public String instrumentedLibrary() {
    return "spark.route.Routes";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-sparkjava", "sparkjava-error", slowRequestThresholdMs);

    registry.register(
        intercept(ROUTES)
            .method("find")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
