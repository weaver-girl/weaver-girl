package com.github.cc11001100.weavergirl.plugins.ghttp;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Google HTTP Client instrumentation plugin. Times a request being executed.
 *
 * <p>{@code HttpRequest} is the class an application builds and {@code execute} and {@code
 * executeAsync} are the calls that send it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class GHttpPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String REQUEST = "com.google.api.client.http.HttpRequest";

  @Override
  public String name() {
    return "ghttp";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.google.api.client.http.HttpRequest";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-ghttp", "ghttp-error", slowRequestThresholdMs);

    registry.register(
        intercept(REQUEST)
            .methodPattern("execute|executeAsync")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
