package com.github.cc11001100.weavergirl.plugins.quartz;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Quartz scheduler instrumentation plugin. Times job execution, which is where a scheduled task hides
 * a slow or failing run.
 *
 * <p>{@code org.quartz.Job} is the interface every job implements, so it is matched by interface.
 * {@code execute} is the method the scheduler calls for each firing.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowJobThreshold} — Slow job threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class QuartzPlugin extends AbstractPlugin {

  private long slowJobThresholdMs = 1000;
  private boolean enabled = true;

  private static final String JOB = "org.quartz.Job";

  @Override
  public String name() {
    return "quartz";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.quartz.Job";
  }

  @Override
  public void init(PluginContext context) {
    slowJobThresholdMs = context.getConfigLong("slowJobThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-quartz", "quartz-error", slowJobThresholdMs);

    registry.register(
        interceptImplementing(JOB)
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
