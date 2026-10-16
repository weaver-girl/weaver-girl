package com.github.cc11001100.weavergirl.plugins.resteasy;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * RESTEasy instrumentation plugin. Times a resource method being invoked.
 *
 * <p>{@code ResourceMethodInvoker} is the class that calls a resource method and {@code
 * invokeOnTarget} is the method that does it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ResteasyPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String INVOKER = "org.jboss.resteasy.core.ResourceMethodInvoker";

  @Override
  public String name() {
    return "resteasy";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.jboss.resteasy.core.ResourceMethodInvoker";
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
        new TimingInterceptor(name(), "slow-resteasy", "resteasy-error", slowRequestThresholdMs);

    registry.register(
        intercept(INVOKER)
            .method("invokeOnTarget")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
