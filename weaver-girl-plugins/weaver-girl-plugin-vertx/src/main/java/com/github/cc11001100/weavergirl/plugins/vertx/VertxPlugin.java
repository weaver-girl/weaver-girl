package com.github.cc11001100.weavergirl.plugins.vertx;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Vert.x instrumentation plugin. Times outbound HTTP calls and web route dispatch.
 *
 * <p>{@code HttpClientRequest} is the interface every outbound request implements and {@code send}
 * is the call that actually transmits it, so it is matched by interface. {@code RoutingContext} is
 * the interface a web request is dispatched through and {@code next} advances it to the next route
 * handler, so it is matched by interface too.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class VertxPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String HTTP_REQUEST = "io.vertx.core.http.HttpClientRequest";
  private static final String ROUTING_CONTEXT = "io.vertx.ext.web.RoutingContext";

  @Override
  public String name() {
    return "vertx";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.vertx.ext.web.RoutingContext";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-vertx", "vertx-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(HTTP_REQUEST)
            .method("send")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(ROUTING_CONTEXT)
            .method("next")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
