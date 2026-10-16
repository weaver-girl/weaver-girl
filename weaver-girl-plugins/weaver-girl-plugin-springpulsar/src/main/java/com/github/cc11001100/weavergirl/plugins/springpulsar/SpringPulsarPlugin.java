package com.github.cc11001100.weavergirl.plugins.springpulsar;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring Pulsar instrumentation plugin. Times a listener handling one message.
 *
 * <p>{@code DefaultPulsarMessageListenerContainer$Listener.dispatchMessageToListener} is where the
 * container hands a received message to the listener, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowMessageThreshold} — Slow message threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SpringPulsarPlugin extends AbstractPlugin {

  private long thresholdMs = 200;
  private boolean enabled = true;

  @Override
  public String name() {
    return "springpulsar";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.pulsar.listener.DefaultPulsarMessageListenerContainer";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowMessageThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-springpulsar", "springpulsar-error", thresholdMs);

    registry.register(
        intercept(
                "org.springframework.pulsar.listener.DefaultPulsarMessageListenerContainer$Listener")
            .method("dispatchMessageToListener")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
