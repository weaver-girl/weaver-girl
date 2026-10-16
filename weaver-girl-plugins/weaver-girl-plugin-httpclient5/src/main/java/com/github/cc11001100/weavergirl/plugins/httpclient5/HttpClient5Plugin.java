package com.github.cc11001100.weavergirl.plugins.httpclient5;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache HttpClient 5 instrumentation plugin. Times one request a client sends.
 *
 * <p>{@code HttpClient.execute} sends a request and blocks for the response, and {@code
 * HttpAsyncClient.execute} sends one without blocking. Both live in the {@code org.apache.hc}
 * packages, which the 4.x client does not use, so each is matched by the interface it implements.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class HttpClient5Plugin extends AbstractPlugin {

  private static final String[] CLIENTS = {"org.apache.hc.client5.http.classic.HttpClient",
          "org.apache.hc.client5.http.async.HttpAsyncClient"};

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "httpclient5";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.hc.client5.http.classic.HttpClient";
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
        new TimingInterceptor(name(), "slow-httpclient5", "httpclient5-error", thresholdMs);

    for (String client : CLIENTS) {
      registry.register(
          interceptImplementing(client)
              .method("execute")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
