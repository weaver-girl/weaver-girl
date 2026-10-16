package com.github.cc11001100.weavergirl.plugins.graphql;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * GraphQL Java instrumentation plugin. Times query execution, which is where a resolver hides a slow
 * or failing fetch.
 *
 * <p>{@code graphql.GraphQL} is a concrete class and {@code execute} is the method every query goes
 * through, so both are matched by name. {@code executeAsync} covers the asynchronous entry.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class GraphQLPlugin extends AbstractPlugin {

  private long slowQueryThresholdMs = 200;
  private boolean enabled = true;

  private static final String GRAPHQL = "graphql.GraphQL";

  @Override
  public String name() {
    return "graphql";
  }

  @Override
  public String instrumentedLibrary() {
    return "graphql.GraphQL";
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
        new TimingInterceptor(name(), "slow-graphql", "graphql-error", slowQueryThresholdMs);

    registry.register(
        intercept(GRAPHQL)
            .methodPattern("execute|executeAsync")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
