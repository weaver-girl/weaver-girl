package com.github.cc11001100.weavergirl.plugins.avaje;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Avaje Jex instrumentation plugin. Times a route handler running.
 *
 * <p>{@code ExchangeHandler} is the interface every route handler implements and {@code handle} is
 * the method called with the request context, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class AvajePlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String HANDLER = "io.avaje.jex.http.ExchangeHandler";

  @Override
  public String name() {
    return "avaje";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.avaje.jex.http.ExchangeHandler";
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
        new TimingInterceptor(name(), "slow-avaje", "avaje-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(HANDLER)
            .method("handle")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
