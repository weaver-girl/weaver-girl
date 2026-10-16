package com.github.cc11001100.weavergirl.plugins.asynchttp;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * AsyncHttpClient instrumentation plugin. Times an asynchronous HTTP request being submitted.
 *
 * <p>{@code AsyncHttpClient} is the interface every client implements and {@code executeRequest} is
 * the method that submits a request with its handler, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class AsyncHttpPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String CLIENT = "org.asynchttpclient.AsyncHttpClient";

  @Override
  public String name() {
    return "asynchttp";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.asynchttpclient.AsyncHttpClient";
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
        new TimingInterceptor(name(), "slow-asynchttp", "asynchttp-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(CLIENT)
            .method("executeRequest")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
