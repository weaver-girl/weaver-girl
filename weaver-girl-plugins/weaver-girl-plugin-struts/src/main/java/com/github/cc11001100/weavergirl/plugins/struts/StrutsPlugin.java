package com.github.cc11001100.weavergirl.plugins.struts;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Struts 2 instrumentation plugin. Times an action running.
 *
 * <p>{@code ActionInvocation} is the interface that drives an action and {@code invokeActionOnly}
 * is the method that calls the action itself, without the interceptor stack around it, so it is
 * matched by interface. Struts 6 and earlier declare the interface in {@code
 * com.opensymphony.xwork2}, Struts 7 moved it to {@code org.apache.struts2}, so both are matched.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowActionThreshold} — Slow action threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class StrutsPlugin extends AbstractPlugin {

  private long slowActionThresholdMs = 1000;
  private boolean enabled = true;

  private static final String[] INVOCATIONS = {
    "com.opensymphony.xwork2.ActionInvocation", "org.apache.struts2.ActionInvocation"
  };

  @Override
  public String name() {
    return "struts";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.opensymphony.xwork2.ActionInvocation";
  }

  @Override
  public void init(PluginContext context) {
    slowActionThresholdMs = context.getConfigLong("slowActionThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-struts", "struts-error", slowActionThresholdMs);

    for (String invocation : INVOCATIONS) {
      registry.register(
          interceptImplementing(invocation)
              .method("invokeActionOnly")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
