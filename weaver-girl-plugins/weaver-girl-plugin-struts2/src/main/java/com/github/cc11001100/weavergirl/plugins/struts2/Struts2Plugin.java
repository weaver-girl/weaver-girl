package com.github.cc11001100.weavergirl.plugins.struts2;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Struts 2.7+ instrumentation plugin. Times an action running.
 *
 * <p>Struts 7 moved {@code ActionInvocation} from {@code com.opensymphony.xwork2} to {@code
 * org.apache.struts2}, so this covers the new package while the {@code struts} plugin covers the
 * old one. {@code invokeActionOnly} calls the action without the interceptor stack, and it is
 * matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowActionThreshold} — Slow action threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class Struts2Plugin extends AbstractPlugin {

  private long slowActionThresholdMs = 1000;
  private boolean enabled = true;

  private static final String INVOCATION = "org.apache.struts2.ActionInvocation";

  @Override
  public String name() {
    return "struts2";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.struts2.ActionInvocation";
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
        new TimingInterceptor(name(), "slow-struts2", "struts2-error", slowActionThresholdMs);

    registry.register(
        interceptImplementing(INVOCATION)
            .method("invokeActionOnly")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
