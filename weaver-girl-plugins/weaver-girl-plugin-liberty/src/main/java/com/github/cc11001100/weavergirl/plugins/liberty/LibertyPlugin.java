package com.github.cc11001100.weavergirl.plugins.liberty;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Open Liberty instrumentation plugin. Times a request being handled by a web application.
 *
 * <p>{@code WebApp} is the class that represents a deployed application and {@code handleRequest}
 * is the method that serves one request, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class LibertyPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String WEB_APP = "com.ibm.ws.webcontainer.webapp.WebApp";

  @Override
  public String name() {
    return "liberty";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.ibm.ws.webcontainer.webapp.WebApp";
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
        new TimingInterceptor(name(), "slow-liberty", "liberty-error", slowRequestThresholdMs);

    registry.register(
        intercept(WEB_APP)
            .method("handleRequest")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
