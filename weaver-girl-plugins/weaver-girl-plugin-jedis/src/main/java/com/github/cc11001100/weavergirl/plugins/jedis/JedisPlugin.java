package com.github.cc11001100.weavergirl.plugins.jedis;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Jedis instrumentation plugin. Times Redis commands sent over a connection.
 *
 * <p>{@code Connection} is the class every Jedis client sends through and {@code sendCommand} is
 * the method that writes a command to the socket, so it is matched by superclass. This sits below
 * the command methods, so one point covers get, set, and every other command.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCommandThreshold} — Slow command threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JedisPlugin extends AbstractPlugin {

  private long slowCommandThresholdMs = 100;
  private boolean enabled = true;

  private static final String CONNECTION = "redis.clients.jedis.Connection";

  @Override
  public String name() {
    return "jedis";
  }

  @Override
  public String instrumentedLibrary() {
    return "redis.clients.jedis.Connection";
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
        new TimingInterceptor(name(), "slow-jedis", "jedis-error", slowCommandThresholdMs);

    registry.register(
        interceptSubclassOf(CONNECTION)
            .method("sendCommand")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
