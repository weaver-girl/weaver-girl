package com.github.cc11001100.weavergirl.plugins.commonshttp;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Commons HttpClient instrumentation plugin. Times one request the 3.x client sends.
 *
 * <p>{@code org.apache.commons.httpclient.HttpClient.executeMethod} is where that client runs a
 * method against a host, so every subclass is matched by its parent class.
 *
 * <p>{@code org.apache.commons.httpclient.HttpMethodDirector.executeMethod} is the package-private
 * class that actually opens the connection, authenticates and follows redirects, so it is matched by
 * its exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class CommonsHttpPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "commonshttp";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.commons.httpclient.HttpClient";
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
        new TimingInterceptor(name(), "slow-commonshttp", "commonshttp-error", thresholdMs);

    registry.register(
        interceptSubclassOf("org.apache.commons.httpclient.HttpClient")
            .method("executeMethod")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
    registry.register(
        intercept("org.apache.commons.httpclient.HttpMethodDirector")
            .method("executeMethod")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
