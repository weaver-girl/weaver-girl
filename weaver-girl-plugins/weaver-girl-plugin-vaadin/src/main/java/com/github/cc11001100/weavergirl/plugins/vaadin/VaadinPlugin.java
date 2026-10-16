package com.github.cc11001100.weavergirl.plugins.vaadin;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Vaadin instrumentation plugin. Times a request being handled.
 *
 * <p>{@code RequestHandler} is the interface every handler in the request chain implements and
 * {@code handleRequest} is the method called for each request, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class VaadinPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String HANDLER = "com.vaadin.flow.server.RequestHandler";

  @Override
  public String name() {
    return "vaadin";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.vaadin.flow.server.RequestHandler";
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
        new TimingInterceptor(name(), "slow-vaadin", "vaadin-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(HANDLER)
            .method("handleRequest")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
