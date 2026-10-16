package com.github.cc11001100.weavergirl.plugins.traced;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * OpenTelemetry legacy @WithSpan instrumentation plugin. Times a method the application annotated for tracing with the annotation from the extension-annotations artifact, which predates the one in instrumentation-annotations.
 *
 * <p>The annotation marks the method the application wants timed, and that method can live on any
 * class, so the class match is a pattern covering everything and the method match is the annotation.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowMethodThreshold} — Slow method threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class TracedPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "traced";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.opentelemetry.extension.annotations.WithSpan";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowMethodThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-traced", "traced-error", thresholdMs);

    registry.register(
        interceptClassPattern(".*")
            .methodAnnotated("io.opentelemetry.extension.annotations.WithSpan")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
