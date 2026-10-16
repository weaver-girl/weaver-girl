package com.github.cc11001100.weavergirl.plugins.undertow;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Undertow instrumentation plugin. Times request handling at the handler chain, the point every
 * request passes through.
 *
 * <p>{@code io.undertow.server.HttpHandler} is an interface implemented by every handler in the
 * chain, so it is matched by interface. {@code handleRequest} is the method the server calls for
 * each exchange.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class UndertowPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String HTTP_HANDLER = "io.undertow.server.HttpHandler";

  @Override
  public String name() {
    return "undertow";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.undertow.server.HttpHandler";
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
        new TimingInterceptor(name(), "slow-undertow", "undertow-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(HTTP_HANDLER)
            .method("handleRequest")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
