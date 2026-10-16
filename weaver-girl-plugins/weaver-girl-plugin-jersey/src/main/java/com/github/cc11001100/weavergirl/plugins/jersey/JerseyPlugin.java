package com.github.cc11001100.weavergirl.plugins.jersey;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Jersey instrumentation plugin. Times a resource method being dispatched.
 *
 * <p>{@code AbstractJavaResourceMethodDispatcher} is the base every resource method dispatcher
 * extends and {@code dispatch} is the method that invokes the resource method, so it is matched by
 * superclass. The class name is unchanged between Jersey 2 and Jersey 3, so one point covers both.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JerseyPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String DISPATCHER =
      "org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher";

  @Override
  public String name() {
    return "jersey";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher";
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
        new TimingInterceptor(name(), "slow-jersey", "jersey-error", slowRequestThresholdMs);

    registry.register(
        interceptSubclassOf(DISPATCHER)
            .method("dispatch")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
