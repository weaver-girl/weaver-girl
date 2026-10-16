package com.github.cc11001100.weavergirl.plugins.elasticapm;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Elastic APM annotation instrumentation plugin. Times a method the application marked for tracing.
 *
 * <p>A method annotated with {@code @Traced} or {@code @CaptureTransaction} is one the application
 * asked to be measured, so it is matched by method annotation.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowMethodThreshold} — Slow method threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ElasticApmPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "elasticapm";
  }

  @Override
  public String instrumentedLibrary() {
    return "co.elastic.apm.api.Traced";
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
        new TimingInterceptor(name(), "slow-elasticapm", "elasticapm-error", thresholdMs);

    for (String annotation :
        new String[] {"co.elastic.apm.api.Traced", "co.elastic.apm.api.CaptureTransaction"}) {
      registry.register(
          interceptClassPattern(".*")
              .methodAnnotated(annotation)
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
