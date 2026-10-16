package com.github.cc11001100.weavergirl.plugins.jms;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * JMS instrumentation plugin. Times message sends and receives across every JMS provider, since the
 * provider implements the interfaces rather than the application.
 *
 * <p>Both the {@code javax.jms} and the {@code jakarta.jms} namespaces are covered, because an
 * application uses one or the other depending on its Java EE generation. The producer and consumer
 * are interfaces, so they are matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowSendThreshold} — Slow send threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JmsPlugin extends AbstractPlugin {

  private long slowSendThresholdMs = 200;
  private boolean enabled = true;

  private static final String[] PRODUCERS = {
    "javax.jms.MessageProducer", "jakarta.jms.MessageProducer"
  };
  private static final String[] CONSUMERS = {
    "javax.jms.MessageConsumer", "jakarta.jms.MessageConsumer"
  };

  @Override
  public String name() {
    return "jms";
  }

  @Override
  public String instrumentedLibrary() {
    return "javax.jms.MessageProducer";
  }

  @Override
  public void init(PluginContext context) {
    slowSendThresholdMs = context.getConfigLong("slowSendThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-jms", "jms-error", slowSendThresholdMs);

    for (String producer : PRODUCERS) {
      registry.register(
          interceptImplementing(producer)
              .method("send")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
    for (String consumer : CONSUMERS) {
      registry.register(
          interceptImplementing(consumer)
              .method("receive")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
