package com.github.cc11001100.weavergirl.plugins.scalaforkjoin;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Scala ForkJoin instrumentation plugin. Times a task the Scala fork-join pool runs.
 *
 * <p>{@code scala.concurrent.forkjoin.ForkJoinTask.exec} is the method the pool calls to run one
 * task, so every subclass is matched by its parent class.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowTaskThreshold} — Slow task threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ScalaForkJoinPlugin extends AbstractPlugin {

  private long thresholdMs = 200;
  private boolean enabled = true;

  @Override
  public String name() {
    return "scalaforkjoin";
  }

  @Override
  public String instrumentedLibrary() {
    return "scala.concurrent.forkjoin.ForkJoinTask";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowTaskThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-scalaforkjoin", "scalaforkjoin-error", thresholdMs);

    registry.register(
        interceptSubclassOf("scala.concurrent.forkjoin.ForkJoinTask")
            .method("exec")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
