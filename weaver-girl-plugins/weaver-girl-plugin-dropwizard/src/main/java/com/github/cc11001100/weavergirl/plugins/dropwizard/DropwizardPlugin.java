package com.github.cc11001100.weavergirl.plugins.dropwizard;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Dropwizard Views instrumentation plugin. Times a view being rendered.
 *
 * <p>{@code ViewRenderer} is the interface every template engine implements and {@code render} is
 * the method that writes the view, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRenderThreshold} — Slow render threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class DropwizardPlugin extends AbstractPlugin {

  private long slowRenderThresholdMs = 200;
  private boolean enabled = true;

  private static final String RENDERER = "io.dropwizard.views.ViewRenderer";

  @Override
  public String name() {
    return "dropwizard";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.dropwizard.views.ViewRenderer";
  }

  @Override
  public void init(PluginContext context) {
    slowRenderThresholdMs = context.getConfigLong("slowRenderThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-dropwizard", "dropwizard-error", slowRenderThresholdMs);

    registry.register(
        interceptImplementing(RENDERER)
            .method("render")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
