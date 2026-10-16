package com.github.cc11001100.weavergirl.plugins.couchbase;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Couchbase instrumentation plugin. Times a request being sent to the cluster core.
 *
 * <p>{@code CouchbaseCore} is the class every bucket and cluster operation funnels into and {@code
 * send} is the method that dispatches a request, so it is matched by exact name. One point covers
 * get, upsert, query, and the rest.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class CouchbasePlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 200;
  private boolean enabled = true;

  private static final String CORE = "com.couchbase.client.core.CouchbaseCore";

  @Override
  public String name() {
    return "couchbase";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.couchbase.client.core.CouchbaseCore";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-couchbase", "couchbase-error", slowRequestThresholdMs);

    registry.register(
        intercept(CORE)
            .method("send")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
