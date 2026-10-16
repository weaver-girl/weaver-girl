package com.github.cc11001100.weavergirl.plugins.cassandra;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Cassandra instrumentation plugin. Times statement execution on the DataStax driver session,
 * which is where a query's latency shows up.
 *
 * <p>{@code CqlSession} is the interface every session implements, so it is matched by interface.
 * {@code execute} runs a statement synchronously and {@code executeAsync} runs it asynchronously.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class CassandraPlugin extends AbstractPlugin {

  private long slowQueryThresholdMs = 200;
  private boolean enabled = true;

  private static final String CQL_SESSION = "com.datastax.oss.driver.api.core.cql.CqlSession";

  @Override
  public String name() {
    return "cassandra";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.datastax.oss.driver.api.core.cql.CqlSession";
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
        new TimingInterceptor(name(), "slow-cassandra", "cassandra-error", slowQueryThresholdMs);

    registry.register(
        interceptImplementing(CQL_SESSION)
            .methodPattern("execute|executeAsync")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
