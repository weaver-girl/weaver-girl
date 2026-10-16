package com.github.cc11001100.weavergirl.plugins.opensearch;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * OpenSearch instrumentation plugin. Times REST requests sent to a cluster.
 *
 * <p>{@code RestClient} is the low-level client every higher-level client sends through, and
 * {@code performRequest} and {@code performRequestAsync} are the methods that execute a request, so
 * it is matched by exact name. One point covers the synchronous and asynchronous paths.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class OpenSearchPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 200;
  private boolean enabled = true;

  private static final String CLIENT = "org.opensearch.client.RestClient";

  @Override
  public String name() {
    return "opensearch";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.opensearch.client.RestClient";
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
        new TimingInterceptor(name(), "slow-opensearch", "opensearch-error", slowRequestThresholdMs);

    registry.register(
        intercept(CLIENT)
            .methodPattern("performRequest|performRequestAsync")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
