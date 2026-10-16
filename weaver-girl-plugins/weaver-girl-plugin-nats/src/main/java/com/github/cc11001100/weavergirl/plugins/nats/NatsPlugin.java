package com.github.cc11001100.weavergirl.plugins.nats;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * NATS instrumentation plugin. Times publishing and message handling.
 *
 * <p>{@code Connection} is the interface every client publishes through, so {@code publish} and
 * {@code request} are matched by interface. {@code MessageHandler} is the interface an application
 * implements to receive a message and {@code onMessage} is its single method, so it is matched by
 * interface too.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowPublishThreshold} — Slow publish threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class NatsPlugin extends AbstractPlugin {

  private long slowPublishThresholdMs = 200;
  private boolean enabled = true;

  private static final String CONNECTION = "io.nats.client.Connection";
  private static final String HANDLER = "io.nats.client.MessageHandler";

  @Override
  public String name() {
    return "nats";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.nats.client.Connection";
  }

  @Override
  public void init(PluginContext context) {
    slowPublishThresholdMs = context.getConfigLong("slowPublishThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-nats", "nats-error", slowPublishThresholdMs);

    registry.register(
        interceptImplementing(CONNECTION)
            .methodPattern("publish|request")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(HANDLER)
            .method("onMessage")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
