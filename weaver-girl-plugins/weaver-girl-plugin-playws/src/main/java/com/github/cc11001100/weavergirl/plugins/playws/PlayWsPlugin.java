package com.github.cc11001100.weavergirl.plugins.playws;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Play WS instrumentation plugin. Times one request the Play web service client sends.
 *
 * <p>Play shades its own copy of the async HTTP client, and {@code
 * play.shaded.ahc.org.asynchttpclient.AsyncHttpClient.execute} is where a request goes out, so
 * every implementation is matched by the interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class PlayWsPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "playws";
  }

  @Override
  public String instrumentedLibrary() {
    return "play.shaded.ahc.org.asynchttpclient.AsyncHttpClient";
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
        new TimingInterceptor(name(), "slow-playws", "playws-error", thresholdMs);

    registry.register(
        interceptImplementing("play.shaded.ahc.org.asynchttpclient.AsyncHttpClient")
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
