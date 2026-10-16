package com.github.cc11001100.weavergirl.plugins.kubernetes;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Kubernetes client instrumentation plugin. Times an API call being executed.
 *
 * <p>{@code ApiClient} is the client every generated API uses and {@code execute} runs a built call against the apiserver while {@code executeAsync}
 * does the same without blocking, so both are matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class KubernetesPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String CLIENT = "io.kubernetes.client.openapi.ApiClient";

  @Override
  public String name() {
    return "kubernetes";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.kubernetes.client.openapi.ApiClient";
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
        new TimingInterceptor(
            name(), "slow-kubernetes", "kubernetes-error", slowRequestThresholdMs);

    for (String method : new String[] {"execute", "executeAsync"}) {
      registry.register(
          intercept(CLIENT)
              .method(method)
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
