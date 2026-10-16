package com.github.cc11001100.weavergirl.plugins.traceanno;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Third-party trace annotation instrumentation plugin. Times a method another agent would trace.
 *
 * <p>A method annotated with one of the trace annotations below was marked by the application for
 * measurement, so it is matched by method annotation. Each vendor ships its annotation under its
 * own class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowMethodThreshold} — Slow method threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class TraceAnnoPlugin extends AbstractPlugin {

  private static final String[] ANNOTATIONS = {
          "com.appoptics.api.ext.LogMethod",
          "com.newrelic.api.agent.Trace",
          "com.signalfx.tracing.api.Trace",
          "com.tracelytics.api.ext.LogMethod",
          "datadog.trace.api.Trace",
          "io.opentracing.contrib.dropwizard.Trace",
          "kamon.annotation.Trace",
          "kamon.annotation.api.Trace",
          "org.springframework.cloud.sleuth.annotation.NewSpan"
  };

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "traceanno";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.newrelic.api.agent.Trace";
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
        new TimingInterceptor(name(), "slow-traceanno", "traceanno-error", thresholdMs);

    for (String annotation : ANNOTATIONS) {
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
