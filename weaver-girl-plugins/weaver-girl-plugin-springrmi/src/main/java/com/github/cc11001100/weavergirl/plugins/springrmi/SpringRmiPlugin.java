package com.github.cc11001100.weavergirl.plugins.springrmi;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring Remoting instrumentation plugin. Times a remote call on both sides.
 *
 * <p>RmiClientInterceptor.invoke makes the outbound call and RmiBasedExporter.invoke dispatches the inbound one, so both are matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCallThreshold} — Slow call threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SpringRmiPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "springrmi";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.remoting.rmi.RmiClientInterceptor";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowCallThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-springrmi", "springrmi-error", thresholdMs);

    registry.register(
        intercept("org.springframework.remoting.rmi.RmiClientInterceptor")
            .method("invoke")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
    registry.register(
        intercept("org.springframework.remoting.rmi.RmiBasedExporter")
            .method("invoke")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
