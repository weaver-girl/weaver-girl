package com.github.cc11001100.weavergirl.plugins.scheduled;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Scheduled-method instrumentation plugin. Times a method a scheduler invokes directly.
 *
 * <p>A method annotated with Scheduled or Schedules, in either Spring or EJB (javax and jakarta), is one the container calls on its own timer, so it is matched by method annotation.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowTaskThreshold} — Slow task threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ScheduledPlugin extends AbstractPlugin {

  private long thresholdMs = 1000;
  private boolean enabled = true;

  @Override
  public String name() {
    return "scheduled";
  }

  @Override
  public String instrumentedLibrary() {
    return "javax.ejb.Schedule";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowTaskThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-scheduled", "scheduled-error", thresholdMs);

    for (String annotation :
        new String[] {
          "org.springframework.scheduling.annotation.Scheduled",
          "org.springframework.scheduling.annotation.Schedules",
          "javax.ejb.Schedule",
          "javax.ejb.Schedules",
          "jakarta.ejb.Schedule",
          "jakarta.ejb.Schedules"
        }) {
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
