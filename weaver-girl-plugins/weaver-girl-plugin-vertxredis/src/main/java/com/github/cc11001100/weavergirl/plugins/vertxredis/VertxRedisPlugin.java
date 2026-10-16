package com.github.cc11001100.weavergirl.plugins.vertxredis;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Vert.x Redis instrumentation plugin. Times one command sent over a Redis connection.
 *
 * <p>{@code RedisStandaloneConnection.send} sends one command and {@code batch} sends several, so
 * both are matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCommandThreshold} — Slow command threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class VertxRedisPlugin extends AbstractPlugin {

  private static final String[] METHODS = {"send",
          "batch"};

  private long thresholdMs = 100;
  private boolean enabled = true;

  @Override
  public String name() {
    return "vertxredis";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.vertx.redis.client.impl.RedisStandaloneConnection";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowCommandThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-vertxredis", "vertxredis-error", thresholdMs);

    for (String method : METHODS) {
      registry.register(
          intercept("io.vertx.redis.client.impl.RedisStandaloneConnection")
              .method(method)
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
