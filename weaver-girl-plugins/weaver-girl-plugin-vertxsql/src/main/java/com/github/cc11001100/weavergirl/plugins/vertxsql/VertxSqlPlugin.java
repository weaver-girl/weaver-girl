package com.github.cc11001100.weavergirl.plugins.vertxsql;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Vert.x SQL client instrumentation plugin. Times one query a pooled client runs.
 *
 * <p>{@code QueryExecutor} runs a query through {@code executeSimpleQuery}, {@code
 * executeExtendedQuery} or {@code executeBatchQuery}, and the class name is the same in client 4
 * and 5, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class VertxSqlPlugin extends AbstractPlugin {

  private static final String[] METHODS = {"executeSimpleQuery",
          "executeExtendedQuery",
          "executeBatchQuery"};

  private long thresholdMs = 200;
  private boolean enabled = true;

  @Override
  public String name() {
    return "vertxsql";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.vertx.sqlclient.impl.QueryExecutor";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowQueryThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-vertxsql", "vertxsql-error", thresholdMs);

    for (String method : METHODS) {
      registry.register(
          intercept("io.vertx.sqlclient.impl.QueryExecutor")
              .method(method)
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
