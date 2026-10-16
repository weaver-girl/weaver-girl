package com.github.cc11001100.weavergirl.plugins.hibernate;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Hibernate instrumentation plugin. Times queries and transaction commits.
 *
 * <p>{@code Query} and {@code Transaction} are interfaces every implementation provides, so they are
 * matched by interface. {@code list} and {@code uniqueResult} run a query, and {@code commit} ends a
 * transaction.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class HibernatePlugin extends AbstractPlugin {

  private long slowQueryThresholdMs = 200;
  private boolean enabled = true;

  private static final String QUERY = "org.hibernate.Query";
  private static final String TRANSACTION = "org.hibernate.Transaction";

  @Override
  public String name() {
    return "hibernate";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.hibernate.Query";
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
        new TimingInterceptor(name(), "slow-hibernate", "hibernate-error", slowQueryThresholdMs);

    registry.register(
        interceptImplementing(QUERY)
            .methodPattern("list|uniqueResult")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(TRANSACTION)
            .method("commit")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
