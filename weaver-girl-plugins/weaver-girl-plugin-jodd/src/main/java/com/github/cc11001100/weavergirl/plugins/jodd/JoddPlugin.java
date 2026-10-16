package com.github.cc11001100.weavergirl.plugins.jodd;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Jodd HTTP instrumentation plugin. Times a request being sent.
 *
 * <p>{@code HttpRequest} is the class an application builds and {@code send} is the method that
 * transmits it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JoddPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String REQUEST = "jodd.http.HttpRequest";

  @Override
  public String name() {
    return "jodd";
  }

  @Override
  public String instrumentedLibrary() {
    return "jodd.http.HttpRequest";
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
        new TimingInterceptor(name(), "slow-jodd", "jodd-error", slowRequestThresholdMs);

    registry.register(
        intercept(REQUEST)
            .method("send")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
