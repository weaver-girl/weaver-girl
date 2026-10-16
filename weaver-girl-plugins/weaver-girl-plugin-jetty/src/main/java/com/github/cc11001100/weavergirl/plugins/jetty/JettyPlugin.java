package com.github.cc11001100.weavergirl.plugins.jetty;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Eclipse Jetty instrumentation plugin. Times request handling at the handler chain, the point every
 * request passes through before it reaches a servlet.
 *
 * <p>{@code org.eclipse.jetty.server.Handler} is an interface implemented by every handler in the
 * chain, so it is matched by interface. {@code handle} is the method the server calls for each
 * request.
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

  private static final String HANDLER = "org.eclipse.jetty.server.Handler";

  @Override
  public String name() {
    return "jetty";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.eclipse.jetty.server.Handler";
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
        interceptImplementing(HANDLER)
            .method("handle")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
