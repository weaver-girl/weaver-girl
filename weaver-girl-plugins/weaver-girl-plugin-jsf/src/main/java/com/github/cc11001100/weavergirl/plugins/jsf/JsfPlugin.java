package com.github.cc11001100.weavergirl.plugins.jsf;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * JSF instrumentation plugin. Times an action event being processed.
 *
 * <p>Mojarra and MyFaces each ship their own {@code ActionListenerImpl} and {@code processAction}
 * is the method that fires the action, so both are matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowActionThreshold} — Slow action threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JsfPlugin extends AbstractPlugin {

  private long slowActionThresholdMs = 200;
  private boolean enabled = true;

  private static final String[] LISTENERS = {
    "com.sun.faces.application.ActionListenerImpl",
    "org.apache.myfaces.application.ActionListenerImpl"
  };

  @Override
  public String name() {
    return "jsf";
  }

  @Override
  public String instrumentedLibrary() {
    return "javax.faces.event.ActionListener";
  }

  @Override
  public void init(PluginContext context) {
    slowActionThresholdMs = context.getConfigLong("slowActionThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-jsf", "jsf-error", slowActionThresholdMs);

    for (String listener : LISTENERS) {
      registry.register(
          intercept(listener)
              .method("processAction")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
