package com.github.cc11001100.weavergirl.plugins.powerjob;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * PowerJob instrumentation plugin. Times a processor running a task.
 *
 * <p>{@code BasicProcessor} is the interface every processor implements and {@code process} is the
 * method the worker calls with the task context, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowJobThreshold} — Slow job threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class PowerJobPlugin extends AbstractPlugin {

  private long slowJobThresholdMs = 1000;
  private boolean enabled = true;

  private static final String PROCESSOR = "tech.powerjob.worker.core.processor.sdk.BasicProcessor";

  @Override
  public String name() {
    return "powerjob";
  }

  @Override
  public String instrumentedLibrary() {
    return "tech.powerjob.worker.core.processor.sdk.BasicProcessor";
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
        new TimingInterceptor(name(), "slow-powerjob", "powerjob-error", slowJobThresholdMs);

    registry.register(
        interceptImplementing(PROCESSOR)
            .method("process")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
