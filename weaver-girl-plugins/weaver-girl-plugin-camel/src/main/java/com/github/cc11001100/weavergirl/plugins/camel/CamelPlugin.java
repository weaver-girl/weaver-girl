package com.github.cc11001100.weavergirl.plugins.camel;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Camel instrumentation plugin. Times an exchange being sent to an endpoint.
 *
 * <p>{@code SendProcessor} sends to a fixed endpoint, {@code SendDynamicProcessor} to a computed
 * one, {@code Enricher} fetches from one, and {@code MulticastProcessor} fans out to several. Each
 * runs the exchange in {@code process}, so the four are matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowExchangeThreshold} — Slow exchange threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class CamelPlugin extends AbstractPlugin {

  private long slowExchangeThresholdMs = 500;
  private boolean enabled = true;

  private static final String[] PROCESSORS = {
    "org.apache.camel.processor.SendProcessor",
    "org.apache.camel.processor.SendDynamicProcessor",
    "org.apache.camel.processor.Enricher",
    "org.apache.camel.processor.MulticastProcessor"
  };

  @Override
  public String name() {
    return "camel";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.camel.processor.SendProcessor";
  }

  @Override
  public void init(PluginContext context) {
    slowExchangeThresholdMs = context.getConfigLong("slowExchangeThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-camel", "camel-error", slowExchangeThresholdMs);

    for (String processor : PROCESSORS) {
      registry.register(
          intercept(processor)
              .method("process")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
