package com.github.cc11001100.weavergirl.plugins.jetty12http;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Jetty 12 HTTP client instrumentation plugin. Times one request the client sends.
 *
 * <p>Jetty 12 moved the send onto the request itself: {@code
 * org.eclipse.jetty.client.transport.HttpRequest.send} is where a request goes out, so it is
 * matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class Jetty12HttpPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "jetty12http";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.eclipse.jetty.client.transport.HttpRequest";
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
        new TimingInterceptor(name(), "slow-jetty12http", "jetty12http-error", thresholdMs);

    registry.register(
        intercept("org.eclipse.jetty.client.transport.HttpRequest")
            .method("send")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
