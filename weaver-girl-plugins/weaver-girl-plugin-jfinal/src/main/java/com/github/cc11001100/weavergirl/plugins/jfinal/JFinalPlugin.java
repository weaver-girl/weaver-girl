package com.github.cc11001100.weavergirl.plugins.jfinal;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * JFinal instrumentation plugin. Times an action running.
 *
 * <p>{@code Invocation} is the class that drives an action and {@code invoke} is the method that
 * calls it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowActionThreshold} — Slow action threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JFinalPlugin extends AbstractPlugin {

  private long slowActionThresholdMs = 1000;
  private boolean enabled = true;

  private static final String INVOCATION = "com.jfinal.aop.Invocation";

  @Override
  public String name() {
    return "jfinal";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.jfinal.aop.Invocation";
  }

  @Override
  public void init(PluginContext context) {
    slowActionThresholdMs = context.getConfigLong("slowActionThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-jfinal", "jfinal-error", slowActionThresholdMs);

    registry.register(
        intercept(INVOCATION)
            .method("invoke")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
