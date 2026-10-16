package com.github.cc11001100.weavergirl.plugins.grails;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Grails instrumentation plugin. Times a controller action running.
 *
 * <p>{@code UrlMappingsInfoHandlerAdapter} is the adapter Spring MVC calls for a Grails controller
 * and {@code handle} is the method that dispatches to the action, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class GrailsPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String ADAPTER = "org.grails.web.mapping.mvc.UrlMappingsInfoHandlerAdapter";

  @Override
  public String name() {
    return "grails";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.grails.web.mapping.mvc.UrlMappingsInfoHandlerAdapter";
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
        new TimingInterceptor(name(), "slow-grails", "grails-error", slowRequestThresholdMs);

    registry.register(
        intercept(ADAPTER)
            .method("handle")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
