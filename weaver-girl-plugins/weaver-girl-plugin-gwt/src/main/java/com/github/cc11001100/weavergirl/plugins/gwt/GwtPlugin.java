package com.github.cc11001100.weavergirl.plugins.gwt;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * GWT RPC instrumentation plugin. Times a remote procedure call being invoked and its response
 * encoded.
 *
 * <p>{@code RPC} is the class that dispatches a GWT RPC call and {@code invokeAndEncodeResponse}
 * is the method that runs it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCallThreshold} — Slow call threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class GwtPlugin extends AbstractPlugin {

  private long slowCallThresholdMs = 500;
  private boolean enabled = true;

  private static final String RPC = "com.google.gwt.user.server.rpc.RPC";

  @Override
  public String name() {
    return "gwt";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.google.gwt.user.server.rpc.RPC";
  }

  @Override
  public void init(PluginContext context) {
    slowCallThresholdMs = context.getConfigLong("slowCallThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-gwt", "gwt-error", slowCallThresholdMs);

    registry.register(
        intercept(RPC)
            .method("invokeAndEncodeResponse")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
