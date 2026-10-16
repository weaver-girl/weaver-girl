package com.github.cc11001100.weavergirl.plugins.hibernatesearch;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Hibernate Search instrumentation plugin. Times a full-text query being executed.
 *
 * <p>Search 5 runs a query through {@code FullTextQuery} and Search 6 through {@code SearchQuery},
 * so both bases are matched by superclass. Search 6 names every execution method with a {@code
 * fetch} prefix, which a pattern covers in one definition.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class HibernateSearchPlugin extends AbstractPlugin {

  private long slowQueryThresholdMs = 200;
  private boolean enabled = true;

  private static final String SEARCH5 = "org.hibernate.search.FullTextQuery";
  private static final String SEARCH6 = "org.hibernate.search.engine.search.query.SearchQuery";

  @Override
  public String name() {
    return "hibernatesearch";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.hibernate.search.FullTextQuery";
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
        new TimingInterceptor(
            name(), "slow-hibernatesearch", "hibernatesearch-error", slowQueryThresholdMs);

    registry.register(
        interceptSubclassOf(SEARCH5)
            .methodPattern("list|scroll|iterate")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        interceptSubclassOf(SEARCH6)
            .methodPattern("^fetch.*")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
