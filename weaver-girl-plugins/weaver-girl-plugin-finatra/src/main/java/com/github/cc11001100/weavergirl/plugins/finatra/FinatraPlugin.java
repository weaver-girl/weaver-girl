package com.github.cc11001100.weavergirl.plugins.finatra;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Finatra instrumentation plugin. Times a matched route running.
 *
 * <p>{@code Route} is the class behind every HTTP route and {@code handleMatch} is the method that
 * runs it for a request, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class FinatraPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String ROUTE = "com.twitter.finatra.http.internal.routing.Route";

  @Override
  public String name() {
    return "finatra";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.twitter.finatra.http.internal.routing.Route";
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
        new TimingInterceptor(name(), "slow-finatra", "finatra-error", slowRequestThresholdMs);

    registry.register(
        intercept(ROUTE)
            .method("handleMatch")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
