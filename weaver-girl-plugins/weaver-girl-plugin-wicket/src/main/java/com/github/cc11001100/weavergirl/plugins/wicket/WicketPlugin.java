package com.github.cc11001100.weavergirl.plugins.wicket;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Wicket instrumentation plugin. Times a request handler running.
 *
 * <p>{@code RequestHandlerExecutor} is the class that runs the resolved handler and {@code execute}
 * is the method that invokes it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class WicketPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String EXECUTOR = "org.apache.wicket.request.RequestHandlerExecutor";

  @Override
  public String name() {
    return "wicket";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.wicket.request.RequestHandlerExecutor";
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
        new TimingInterceptor(name(), "slow-wicket", "wicket-error", slowRequestThresholdMs);

    registry.register(
        intercept(EXECUTOR)
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
