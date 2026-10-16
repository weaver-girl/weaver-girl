package com.github.cc11001100.weavergirl.plugins.lettuce;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Lettuce instrumentation plugin. Times Redis commands dispatched to a connection.
 *
 * <p>{@code RedisChannelHandler} is the class that writes commands onto the Netty channel and
 * {@code dispatch} is the method that hands a command to it, so it is matched by superclass. This
 * sits below the synchronous, asynchronous, and reactive command APIs, so one point covers all of
 * them.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCommandThreshold} — Slow command threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class LettucePlugin extends AbstractPlugin {

  private long slowCommandThresholdMs = 100;
  private boolean enabled = true;

  private static final String CHANNEL_HANDLER = "io.lettuce.core.protocol.RedisChannelHandler";

  @Override
  public String name() {
    return "lettuce";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.lettuce.core.protocol.RedisChannelHandler";
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
        new TimingInterceptor(name(), "slow-lettuce", "lettuce-error", slowCommandThresholdMs);

    registry.register(
        interceptSubclassOf(CHANNEL_HANDLER)
            .method("dispatch")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
