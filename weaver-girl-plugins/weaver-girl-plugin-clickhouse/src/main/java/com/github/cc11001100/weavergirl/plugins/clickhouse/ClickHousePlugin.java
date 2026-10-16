package com.github.cc11001100.weavergirl.plugins.clickhouse;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * ClickHouse instrumentation plugin. Times query execution on both client generations, since an
 * application uses one or the other.
 *
 * <p>The v1 client {@code com.clickhouse.client.ClickHouseClient} is an interface, so it is matched
 * by interface. The v2 client {@code com.clickhouse.client.api.Client} is a concrete class matched by
 * name. {@code query} is the execution method on both.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ClickHousePlugin extends AbstractPlugin {

  private long slowQueryThresholdMs = 200;
  private boolean enabled = true;

  private static final String CLIENT_V1 = "com.clickhouse.client.ClickHouseClient";
  private static final String CLIENT_V2 = "com.clickhouse.client.api.Client";

  @Override
  public String name() {
    return "clickhouse";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.clickhouse.client.ClickHouseClient";
  }

  @Override
  public void init(PluginContext context) {
    slowQueryThresholdMs = context.getConfigLong("slowQueryThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-clickhouse", "clickhouse-error", slowQueryThresholdMs);

    registry.register(
        interceptImplementing(CLIENT_V1)
            .method("query")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        intercept(CLIENT_V2)
            .method("query")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
