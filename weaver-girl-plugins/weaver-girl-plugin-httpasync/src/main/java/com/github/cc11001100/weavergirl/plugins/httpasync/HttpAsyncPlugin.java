package com.github.cc11001100.weavergirl.plugins.httpasync;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache HttpAsyncClient instrumentation plugin. Times an asynchronous HTTP request being submitted.
 *
 * <p>{@code HttpAsyncClient} is the interface every async client implements and {@code execute} is
 * the method that submits the request, so it is matched by interface. {@code HttpPipeliningClient}
 * extends that interface but declares its own {@code execute} for a batch of requests, so it is
 * matched separately.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class HttpAsyncPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "httpasync";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.http.nio.client.HttpAsyncClient";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-httpasync", "httpasync-error", thresholdMs);

    for (String type :
        new String[] {
          "org.apache.http.nio.client.HttpAsyncClient",
          "org.apache.http.nio.client.HttpPipeliningClient"
        }) {
      registry.register(
          interceptImplementing(type)
              .method("execute")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
