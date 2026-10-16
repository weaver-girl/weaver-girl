package com.github.cc11001100.weavergirl.plugins.ninghttp;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Ning AsyncHttpClient instrumentation plugin. Times one request the 1.x client sends.
 *
 * <p>{@code com.ning.http.client.AsyncHttpClient.executeRequest} is where that client sends a
 * request, so it is matched by class name. The 2.x client moved to the {@code org.asynchttpclient}
 * package and is covered separately.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class NingHttpPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "ninghttp";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.ning.http.client.AsyncHttpClient";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-ninghttp", "ninghttp-error", thresholdMs);

    registry.register(
        intercept("com.ning.http.client.AsyncHttpClient")
            .method("executeRequest")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
