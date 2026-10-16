package com.github.cc11001100.weavergirl.plugins.scheduling;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring scheduling instrumentation plugin. Times a scheduled task being run.
 *
 * <p>DelegatingErrorHandlingRunnable is the wrapper every scheduled task runs inside and run is what invokes it, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowTaskThreshold} — Slow task threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SchedulingPlugin extends AbstractPlugin {

  private long thresholdMs = 1000;
  private boolean enabled = true;

  @Override
  public String name() {
    return "scheduling";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.scheduling.support.DelegatingErrorHandlingRunnable";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowTaskThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-scheduling", "scheduling-error", thresholdMs);

    registry.register(
        intercept("org.springframework.scheduling.support.DelegatingErrorHandlingRunnable")
            .method("run")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
