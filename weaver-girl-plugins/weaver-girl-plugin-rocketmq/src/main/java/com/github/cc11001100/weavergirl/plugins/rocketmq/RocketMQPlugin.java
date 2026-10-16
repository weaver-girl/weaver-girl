package com.github.cc11001100.weavergirl.plugins.rocketmq;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apache RocketMQ instrumentation plugin. Times message sends on the producer and message handling
 * on the consumer, and reports sends or handlers that run longer than the threshold.
 *
 * <p>{@code DefaultMQProducer} and {@code DefaultMQPushConsumer} are concrete classes, so they are
 * matched by name. {@code MessageListenerConcurrently} and {@code MessageListenerOrderly} are
 * interfaces implemented by application code, so they are matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowSendThreshold} — Slow send threshold in ms (default: 200)
 *   <li>{@code slowConsumeThreshold} — Slow consume threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class RocketMQPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(RocketMQPlugin.class);

  private long slowSendThresholdMs = 200;
  private long slowConsumeThresholdMs = 1000;
  private boolean enabled = true;

  private static final String PRODUCER = "org.apache.rocketmq.client.producer.DefaultMQProducer";

  /**
   * The pull loop lives on the implementation, not on {@code DefaultMQPushConsumer}, which only
   * delegates. Matching the wrapper would never fire.
   */
  private static final String PUSH_CONSUMER_IMPL =
      "org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl";
  private static final String LISTENER_CONCURRENTLY =
      "org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently";
  private static final String LISTENER_ORDERLY =
      "org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly";

  @Override
  public String name() {
    return "rocketmq";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.rocketmq.client.producer.DefaultMQProducer";
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

    Interceptor sendInterceptor = timingInterceptor(slowSendThresholdMs, "slow-rocketmq-send", "rocketmq-error");
    Interceptor consumeInterceptor =
        timingInterceptor(slowConsumeThresholdMs, "slow-rocketmq-consume", "rocketmq-error");

    registry.register(
        intercept(PRODUCER)
            .methodPattern("send|sendOneway")
            .around(sendInterceptor::before, sendInterceptor::after)
            .onException(sendInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        intercept(PUSH_CONSUMER_IMPL)
            .method("pullMessage")
            .around(sendInterceptor::before, sendInterceptor::after)
            .onException(sendInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(LISTENER_CONCURRENTLY)
            .method("consumeMessage")
            .around(consumeInterceptor::before, consumeInterceptor::after)
            .onException(consumeInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(LISTENER_ORDERLY)
            .method("consumeMessage")
            .around(consumeInterceptor::before, consumeInterceptor::after)
            .onException(consumeInterceptor::onException)
            .priority(10)
            .build());
  }

  private Interceptor timingInterceptor(long thresholdMs, String slowType, String errorType) {
    return new Interceptor() {
      private final ThreadLocal<Long> startTime = new ThreadLocal<>();

      @Override
      public void before(MethodInvocation inv) {
        startTime.set(System.nanoTime());
      }

      @Override
      public void after(MethodInvocation inv) {
        Long start = startTime.get();
        startTime.remove();
        if (start == null) return;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (elapsedMs >= thresholdMs) {
          log.warn(
              "[SLOW-ROCKETMQ] {}.{} took {}ms",
              inv.getTargetClass().getSimpleName(),
              inv.getMethodName(),
              elapsedMs);
          InterceptorEventPublisher.getInstance()
              .publish(
                  InterceptorEvent.builder()
                      .type(slowType)
                      .plugin(name())
                      .className(inv.getTargetClass().getSimpleName())
                      .methodName(inv.getMethodName())
                      .durationMs(elapsedMs)
                      .build());
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        startTime.remove();
        Throwable thrown = inv.getThrowable();
        log.warn(
            "[ROCKETMQ-ERROR] {}.{} threw: {}",
            inv.getTargetClass().getSimpleName(),
            inv.getMethodName(),
            thrown != null ? thrown.getMessage() : "unknown");
        InterceptorEventPublisher.getInstance()
            .publish(
                InterceptorEvent.builder()
                    .type(errorType)
                    .plugin(name())
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .attribute("error", thrown != null ? thrown.getMessage() : "")
                    .build());
      }
    };
  }
}
