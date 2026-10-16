package com.github.cc11001100.weavergirl.plugins.quarkus;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Quarkus RESTEasy Reactive instrumentation plugin. Times a request being processed.
 *
 * <p>{@code AbstractResteasyReactiveContext} is the base every request context extends and {@code
 * run} is the method that drives the request through the handler chain, so it is matched by
 * superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class QuarkusPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String CONTEXT =
      "org.jboss.resteasy.reactive.common.core.AbstractResteasyReactiveContext";

  @Override
  public String name() {
    return "quarkus";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.jboss.resteasy.reactive.common.core.AbstractResteasyReactiveContext";
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
        new TimingInterceptor(name(), "slow-quarkus", "quarkus-error", slowRequestThresholdMs);

    registry.register(
        interceptSubclassOf(CONTEXT)
            .method("run")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
