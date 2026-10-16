package com.github.cc11001100.weavergirl.plugins.dbcp;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Commons DBCP instrumentation plugin. Times borrowing a connection.
 *
 * <p>{@code BasicDataSource} is the pool an application borrows from and {@code getConnection} is
 * the call that blocks while the pool is exhausted, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowConnectionThreshold} — Slow borrow threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class DbcpPlugin extends AbstractPlugin {

  private long slowConnectionThresholdMs = 100;
  private boolean enabled = true;

  private static final String DATA_SOURCE = "org.apache.commons.dbcp2.BasicDataSource";

  @Override
  public String name() {
    return "dbcp";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.commons.dbcp2.BasicDataSource";
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
        new TimingInterceptor(name(), "slow-dbcp", "dbcp-error", slowConnectionThresholdMs);

    registry.register(
        intercept(DATA_SOURCE)
            .method("getConnection")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
