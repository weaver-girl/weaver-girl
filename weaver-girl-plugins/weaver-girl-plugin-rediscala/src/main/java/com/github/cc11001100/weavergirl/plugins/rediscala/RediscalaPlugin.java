package com.github.cc11001100.weavergirl.plugins.rediscala;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * rediscala instrumentation plugin. Times a Redis command being sent.
 *
 * <p>Every command goes through one of the request bases and {@code send} is the method that
 * transmits it, so the four bases are matched by superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCommandThreshold} — Slow command threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class RediscalaPlugin extends AbstractPlugin {

  private long slowCommandThresholdMs = 100;
  private boolean enabled = true;

  private static final String[] BASES = {
    "redis.ActorRequest", "redis.Request", "redis.BufferedRequest", "redis.RoundRobinPoolRequest"
  };

  @Override
  public String name() {
    return "rediscala";
  }

  @Override
  public String instrumentedLibrary() {
    return "redis.ActorRequest";
  }

  @Override
  public void init(PluginContext context) {
    slowCommandThresholdMs = context.getConfigLong("slowCommandThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-rediscala", "rediscala-error", slowCommandThresholdMs);

    for (String base : BASES) {
      registry.register(
          interceptSubclassOf(base)
              .method("send")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
