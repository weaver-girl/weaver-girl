package com.github.cc11001100.weavergirl.plugins.azurecore;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Azure Core instrumentation plugin. Times an HTTP request being sent.
 *
 * <p>{@code HttpClient} is the interface every Azure HTTP client implements and {@code send} is the
 * method that transmits the request, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class AzureCorePlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String CLIENT = "com.azure.core.http.HttpClient";

  @Override
  public String name() {
    return "azurecore";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.azure.core.http.HttpClient";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-azurecore", "azurecore-error", slowRequestThresholdMs);

    for (String method : new String[] {"send", "sendSync"}) {
      registry.register(
          interceptImplementing(CLIENT)
              .method(method)
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
