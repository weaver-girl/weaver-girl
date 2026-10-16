package com.github.cc11001100.weavergirl.plugins.googlehttp;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Google HTTP Client instrumentation plugin. Times an outbound HTTP request being executed.
 *
 * <p>HttpRequest is the class every Google API request goes through and execute is the method that sends it and reads the response, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class GoogleHttpPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  private static final String TARGET = "com.google.api.client.http.HttpRequest";

  @Override
  public String name() {
    return "googlehttp";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.google.api.client.http.HttpRequest";
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
        new TimingInterceptor(name(), "slow-googlehttp", "googlehttp-error", thresholdMs);

    registry.register(
        intercept(TARGET)
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
