package com.github.cc11001100.weavergirl.plugins.memcached;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spymemcached instrumentation plugin. Times cache operations.
 *
 * <p>{@code MemcachedClient} is the class an application calls and its public methods ({@code get},
 * {@code set}, {@code add}, {@code delete}, and the rest) are the operations, so it is matched by
 * exact name with a method pattern. {@code flush} only clears stats and is left out.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowOperationThreshold} — Slow operation threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class MemcachedPlugin extends AbstractPlugin {

  private long slowOperationThresholdMs = 100;
  private boolean enabled = true;

  private static final String CLIENT = "net.spy.memcached.MemcachedClient";

  @Override
  public String name() {
    return "memcached";
  }

  @Override
  public String instrumentedLibrary() {
    return "net.spy.memcached.MemcachedClient";
  }

  @Override
  public void init(PluginContext context) {
    slowOperationThresholdMs = context.getConfigLong("slowOperationThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-memcached", "memcached-error", slowOperationThresholdMs);

    registry.register(
        intercept(CLIENT)
            .methodPattern("get|set|add|replace|delete|touch|incr|decr|append|prepend")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
