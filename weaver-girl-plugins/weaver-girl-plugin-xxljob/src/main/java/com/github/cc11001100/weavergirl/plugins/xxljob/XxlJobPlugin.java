package com.github.cc11001100.weavergirl.plugins.xxljob;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * xxl-job instrumentation plugin. Times handler execution, which is where a scheduled task hides a
 * slow or failing run.
 *
 * <p>{@code IJobHandler} is the abstract base every handler extends, in both the 1.x and 2.x lines,
 * so it is matched by superclass. {@code execute} is the method the executor calls.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowJobThreshold} — Slow job threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class XxlJobPlugin extends AbstractPlugin {

  private long slowJobThresholdMs = 1000;
  private boolean enabled = true;

  private static final String JOB_HANDLER = "com.xxl.job.core.handler.IJobHandler";

  @Override
  public String name() {
    return "xxljob";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.xxl.job.core.handler.IJobHandler";
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
        new TimingInterceptor(name(), "slow-xxljob", "xxljob-error", slowJobThresholdMs);

    registry.register(
        interceptSubclassOf(JOB_HANDLER)
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
