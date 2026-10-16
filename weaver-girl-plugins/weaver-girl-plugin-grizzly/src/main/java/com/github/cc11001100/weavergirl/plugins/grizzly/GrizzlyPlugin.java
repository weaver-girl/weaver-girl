package com.github.cc11001100.weavergirl.plugins.grizzly;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Grizzly instrumentation plugin. Times an HTTP request being served.
 *
 * <p>{@code HttpHandler} is the abstract base every handler extends and {@code service} is the
 * method that handles one request, so it is matched by superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class GrizzlyPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String HANDLER = "org.glassfish.grizzly.http.server.HttpHandler";

  @Override
  public String name() {
    return "grizzly";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.glassfish.grizzly.http.server.HttpHandler";
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
        new TimingInterceptor(name(), "slow-grizzly", "grizzly-error", slowRequestThresholdMs);

    registry.register(
        interceptSubclassOf(HANDLER)
            .method("service")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
