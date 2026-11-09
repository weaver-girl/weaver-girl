package com.github.cc11001100.weavergirl.plugins.jetty;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Eclipse Jetty instrumentation plugin. Times each request at {@code HttpChannel.handle()}, the
 * channel method Jetty 9 calls once per HTTP request.
 *
 * <p>Weaving every {@code Handler.handle} implementation skips or corrupts servlet dispatch
 * (HTTP 404 for mapped servlets). The channel is a single concrete class, so the servlet
 * mappings stay intact.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JettyPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String HTTP_CHANNEL = "org.eclipse.jetty.server.HttpChannel";

  @Override
  public String name() {
    return "jetty";
  }

  @Override
  public String instrumentedLibrary() {
    return HTTP_CHANNEL;
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
        new TimingInterceptor(name(), "slow-jetty", "jetty-error", slowRequestThresholdMs);

    registry.register(
        intercept(HTTP_CHANNEL)
            .method("handle")
            .before(timing::before)
            .after(timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
