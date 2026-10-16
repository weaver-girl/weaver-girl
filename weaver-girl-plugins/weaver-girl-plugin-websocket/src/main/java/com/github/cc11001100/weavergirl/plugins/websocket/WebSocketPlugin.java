package com.github.cc11001100.weavergirl.plugins.websocket;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Jakarta WebSocket instrumentation plugin. Times a message being handled by an endpoint.
 *
 * <p>An endpoint class is the one annotated with ServerEndpoint, and the method that receives a message is the one annotated with OnMessage. Both the javax and the jakarta annotation names are matched.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowMessageThreshold} — Slow message threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class WebSocketPlugin extends AbstractPlugin {

  private long thresholdMs = 200;
  private boolean enabled = true;

  @Override
  public String name() {
    return "websocket";
  }

  @Override
  public String instrumentedLibrary() {
    return "javax.websocket.server.ServerEndpoint";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowMessageThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-websocket", "websocket-error", thresholdMs);

    for (String annotation : new String[] {"javax.websocket.server.ServerEndpoint", "jakarta.websocket.server.ServerEndpoint"}) {
      registry.register(
          interceptAnnotated(annotation)
              .methodAnnotated("javax.websocket.OnMessage")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
      registry.register(
          interceptAnnotated(annotation)
              .methodAnnotated("jakarta.websocket.OnMessage")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }

  }
}
