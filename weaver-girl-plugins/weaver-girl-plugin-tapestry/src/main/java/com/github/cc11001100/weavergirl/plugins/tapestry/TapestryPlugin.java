package com.github.cc11001100.weavergirl.plugins.tapestry;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Tapestry instrumentation plugin. Times a component event being handled.
 *
 * <p>{@code ComponentPageElementImpl} is the class behind every component on a page and {@code
 * processEventTriggering} is the method that fires its event handler, so it is matched by exact
 * name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowEventThreshold} — Slow event threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class TapestryPlugin extends AbstractPlugin {

  private long slowEventThresholdMs = 200;
  private boolean enabled = true;

  private static final String COMPONENT =
      "org.apache.tapestry5.internal.structure.ComponentPageElementImpl";

  @Override
  public String name() {
    return "tapestry";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.tapestry5.internal.structure.ComponentPageElementImpl";
  }

  @Override
  public void init(PluginContext context) {
    slowEventThresholdMs = context.getConfigLong("slowEventThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-tapestry", "tapestry-error", slowEventThresholdMs);

    registry.register(
        intercept(COMPONENT)
            .method("processEventTriggering")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
