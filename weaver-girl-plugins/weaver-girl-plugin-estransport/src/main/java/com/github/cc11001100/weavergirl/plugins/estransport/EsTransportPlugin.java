package com.github.cc11001100.weavergirl.plugins.estransport;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Elasticsearch transport client instrumentation plugin. Times one request sent over the transport
 * protocol.
 *
 * <p>{@code AbstractClient.execute} is where the transport client dispatches an action, so it is
 * matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class EsTransportPlugin extends AbstractPlugin {

  private long thresholdMs = 200;
  private boolean enabled = true;

  @Override
  public String name() {
    return "estransport";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.elasticsearch.client.support.AbstractClient";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowRequestThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-estransport", "estransport-error", thresholdMs);

    registry.register(
        intercept("org.elasticsearch.client.support.AbstractClient")
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
