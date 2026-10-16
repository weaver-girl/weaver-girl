package com.github.cc11001100.weavergirl.plugins.geode;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Geode instrumentation plugin. Times region operations.
 *
 * <p>{@code Region} is the interface every region implements and {@code get}, {@code put}, and
 * {@code remove} are the operations an application calls, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowOperationThreshold} — Slow operation threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class GeodePlugin extends AbstractPlugin {

  private long slowOperationThresholdMs = 100;
  private boolean enabled = true;

  private static final String REGION = "org.apache.geode.cache.Region";

  @Override
  public String name() {
    return "geode";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.geode.cache.Region";
  }

  @Override
  public void init(PluginContext context) {
    slowOperationThresholdMs = context.getConfigLong("slowOperationThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-geode", "geode-error", slowOperationThresholdMs);

    registry.register(
        interceptImplementing(REGION)
            .methodPattern("get|put|remove")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
