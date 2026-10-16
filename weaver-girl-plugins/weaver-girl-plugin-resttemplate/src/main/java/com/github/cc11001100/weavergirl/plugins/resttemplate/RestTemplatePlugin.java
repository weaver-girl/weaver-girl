package com.github.cc11001100.weavergirl.plugins.resttemplate;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring RestTemplate instrumentation plugin. Times an outbound HTTP request being executed.
 *
 * <p>{@code ClientHttpRequest} is the interface every request implementation implements and {@code
 * execute} is the method that sends it and reads the response, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class RestTemplatePlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String REQUEST = "org.springframework.http.client.ClientHttpRequest";

  @Override
  public String name() {
    return "resttemplate";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.http.client.ClientHttpRequest";
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
            name(), "slow-resttemplate", "resttemplate-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(REQUEST)
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
