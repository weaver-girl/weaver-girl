package com.github.cc11001100.weavergirl.plugins.c3p0;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * c3p0 connection pool instrumentation plugin. Times borrowing a connection.
 *
 * <p>{@code AbstractPoolBackedDataSource} is the base {@code ComboPooledDataSource} extends and
 * {@code getConnection} is the call that blocks while the pool is exhausted, so it is matched by
 * superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowConnectionThreshold} — Slow borrow threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class C3p0Plugin extends AbstractPlugin {

  private long slowConnectionThresholdMs = 100;
  private boolean enabled = true;

  private static final String DATA_SOURCE = "com.mchange.v2.c3p0.impl.AbstractPoolBackedDataSource";

  @Override
  public String name() {
    return "c3p0";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.mchange.v2.c3p0.impl.AbstractPoolBackedDataSource";
  }

  @Override
  public void init(PluginContext context) {
    slowConnectionThresholdMs = context.getConfigLong("slowConnectionThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-c3p0", "c3p0-error", slowConnectionThresholdMs);

    registry.register(
        interceptSubclassOf(DATA_SOURCE)
            .method("getConnection")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
