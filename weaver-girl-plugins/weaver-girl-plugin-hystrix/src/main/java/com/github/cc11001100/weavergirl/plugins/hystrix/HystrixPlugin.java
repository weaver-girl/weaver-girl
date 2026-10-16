package com.github.cc11001100.weavergirl.plugins.hystrix;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Hystrix instrumentation plugin. Times command execution and fallback.
 *
 * <p>{@code HystrixCommand} and {@code HystrixObservableCommand} are the abstract bases every
 * command extends, so they are matched by superclass. {@code run} is the work and {@code
 * getFallback} is what runs when it fails.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCommandThreshold} — Slow command threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class HystrixPlugin extends AbstractPlugin {

  private long slowCommandThresholdMs = 500;
  private boolean enabled = true;

  private static final String[] COMMANDS = {
    "com.netflix.hystrix.HystrixCommand", "com.netflix.hystrix.HystrixObservableCommand"
  };

  @Override
  public String name() {
    return "hystrix";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.netflix.hystrix.HystrixCommand";
  }

  @Override
  public void init(PluginContext context) {
    slowCommandThresholdMs = context.getConfigLong("slowCommandThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-hystrix", "hystrix-error", slowCommandThresholdMs);

    for (String command : COMMANDS) {
      registry.register(
          interceptSubclassOf(command)
              .methodPattern("run|getFallback")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
