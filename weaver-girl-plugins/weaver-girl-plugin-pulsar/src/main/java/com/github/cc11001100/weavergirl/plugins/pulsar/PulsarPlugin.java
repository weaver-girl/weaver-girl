package com.github.cc11001100.weavergirl.plugins.pulsar;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Pulsar instrumentation plugin. Times message sends on the producer and message handling on
 * the consumer listener.
 *
 * <p>{@code ProducerImpl} is the concrete producer and {@code sendAsync} is the method every send
 * goes through, so it is matched by name. {@code MessageListener} is an interface implemented by
 * application code, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowSendThreshold} — Slow send threshold in ms (default: 200)
 *   <li>{@code slowConsumeThreshold} — Slow consume threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class PulsarPlugin extends AbstractPlugin {

  private long slowSendThresholdMs = 200;
  private long slowConsumeThresholdMs = 1000;
  private boolean enabled = true;

  private static final String PRODUCER = "org.apache.pulsar.client.impl.ProducerImpl";
  private static final String LISTENER = "org.apache.pulsar.client.api.MessageListener";

  @Override
  public String name() {
    return "pulsar";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.pulsar.client.impl.ProducerImpl";
  }

  @Override
  public void init(PluginContext context) {
    slowSendThresholdMs = context.getConfigLong("slowSendThreshold", 200);
    slowConsumeThresholdMs = context.getConfigLong("slowConsumeThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor send =
        new TimingInterceptor(name(), "slow-pulsar-send", "pulsar-error", slowSendThresholdMs);
    TimingInterceptor consume =
        new TimingInterceptor(name(), "slow-pulsar-consume", "pulsar-error", slowConsumeThresholdMs);

    registry.register(
        intercept(PRODUCER)
            .method("sendAsync")
            .around(send::before, send::after)
            .onException(send::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(LISTENER)
            .method("received")
            .around(consume::before, consume::after)
            .onException(consume::onException)
            .priority(10)
            .build());
  }
}
