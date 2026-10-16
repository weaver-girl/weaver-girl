package com.github.cc11001100.weavergirl.plugins.springkafka;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring Kafka instrumentation plugin. Times a listener handling one batch of records.
 *
 * <p>{@code KafkaMessageListenerContainer$ListenerConsumer.invokeBatchOnMessageWithRecordsOrList}
 * is where the container hands a polled batch to the listener, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowBatchThreshold} — Slow batch threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SpringKafkaPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "springkafka";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.kafka.listener.KafkaMessageListenerContainer";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowBatchThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-springkafka", "springkafka-error", thresholdMs);

    registry.register(
        intercept(
                "org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer")
            .method("invokeBatchOnMessageWithRecordsOrList")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
