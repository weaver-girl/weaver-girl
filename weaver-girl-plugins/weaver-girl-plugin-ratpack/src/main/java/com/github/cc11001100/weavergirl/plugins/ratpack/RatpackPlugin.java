package com.github.cc11001100.weavergirl.plugins.ratpack;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Ratpack instrumentation plugin. Times an outbound HTTP request being sent.
 *
 * <p>{@code RequestActionSupport} is the base the HTTP client actions extend and {@code send} is
 * the method that transmits the request, so it is matched by superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class RatpackPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String ACTION = "ratpack.http.client.internal.RequestActionSupport";

  @Override
  public String name() {
    return "ratpack";
  }

  @Override
  public String instrumentedLibrary() {
    return "ratpack.http.client.internal.RequestActionSupport";
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
        new TimingInterceptor(name(), "slow-ratpack", "ratpack-error", slowRequestThresholdMs);

    registry.register(
        interceptSubclassOf(ACTION)
            .method("send")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
