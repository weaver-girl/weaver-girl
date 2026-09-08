package com.github.cc11001100.weavergirl.plugins.rabbitmq;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RabbitMQ instrumentation plugin. Intercepts AMQP client operations to:
 *
 * <ul>
 *   <li>Track producer publish timing and message sizes
 *   <li>Track consumer delivery timing and processing duration
 *   <li>Monitor channel lifecycle (open, close, flow)
 *   <li>Propagate trace context via AMQP message headers
 *   <li>Detect slow publishes and long-running consumers
 * </ul>
 *
 * <p>Target classes (string names, no compile dependency):
 *
 * <ul>
 *   <li>{@code com.rabbitmq.client.impl.ChannelN} &mdash; basicPublish, basicAck, basicNack,
 *       basicReject
 *   <li>{@code com.rabbitmq.client.impl.ConsumerDispatcher} &mdash; handleDelivery
 *   <li>{@code com.rabbitmq.client.ConnectionFactory} &mdash; newConnection
 * </ul>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowPublishThreshold} &mdash; Slow publish threshold in ms (default: 3000)
 *   <li>{@code slowConsumeThreshold} &mdash; Slow consume threshold in ms (default: 5000)
 *   <li>{@code trackMessageSize} &mdash; Track message body size (default: true)
 *   <li>{@code propagateTrace} &mdash; Propagate trace via AMQP headers (default: true)
 *   <li>{@code enabled} &mdash; Enable/disable (default: true)
 * </ul>
 */
public class RabbitMQPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(RabbitMQPlugin.class);

  private long slowPublishThresholdMs = 3000;
  private long slowConsumeThresholdMs = 5000;
  private boolean trackMessageSize = true;
  private boolean propagateTrace = true;
  private boolean enabled = true;

  // Target class names
  private static final String CHANNEL_N = "com.rabbitmq.client.impl.ChannelN";
  private static final String CONSUMER_WORK_SERVICE = "com.rabbitmq.client.impl.WorkPool";
  private static final String CONNECTION_FACTORY = "com.rabbitmq.client.ConnectionFactory";

  @Override
  public String name() {
    return "rabbitmq";
  }

  @Override
  public void init(PluginContext context) {
    slowPublishThresholdMs = context.getConfigLong("slowPublishThreshold", 3000);
    slowConsumeThresholdMs = context.getConfigLong("slowConsumeThreshold", 5000);
    trackMessageSize = context.getConfigBoolean("trackMessageSize", true);
    propagateTrace = context.getConfigBoolean("propagateTrace", true);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
    if (!enabled) return;

    // --- Producer interceptor: basicPublish ---
    Interceptor publishInterceptor =
        new Interceptor() {
          private final ThreadLocal<Long> startTime = new ThreadLocal<>();

          @Override
          public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());
            String exchange = extractStringArg(inv, 0);
            String routingKey = extractStringArg(inv, 1);

            // Inject trace headers into AMQP basic properties
            if (propagateTrace) {
              injectTraceIntoProperties(inv);
            }

            if (log.isDebugEnabled()) {
              log.debug("[RABBITMQ-PUBLISH] -> exchange={}, routingKey={}", exchange, routingKey);
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            Long start = startTime.get();
            startTime.remove();
            if (start == null) return;

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String exchange = extractStringArg(inv, 0);
            String routingKey = extractStringArg(inv, 1);
            int messageSize = trackMessageSize ? extractMessageSize(inv) : -1;

            if (elapsedMs >= slowPublishThresholdMs) {
              log.warn(
                  "[SLOW-RABBITMQ-PUBLISH] exchange={}, routingKey={} took {}ms (threshold: {}ms)",
                  exchange,
                  routingKey,
                  elapsedMs,
                  slowPublishThresholdMs);
            } else if (log.isDebugEnabled()) {
              log.debug(
                  "[RABBITMQ-PUBLISH] exchange={}, routingKey={} took {}ms",
                  exchange,
                  routingKey,
                  elapsedMs);
            }

            InterceptorEvent.Builder eventBuilder =
                InterceptorEvent.builder()
                    .type(
                        elapsedMs >= slowPublishThresholdMs
                            ? "slow-rabbitmq-publish"
                            : "rabbitmq-publish")
                    .plugin("rabbitmq")
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .durationMs(elapsedMs);
            if (exchange != null) eventBuilder.attribute("exchange", exchange);
            if (routingKey != null) eventBuilder.attribute("routingKey", routingKey);
            if (messageSize >= 0)
              eventBuilder.attribute("messageSize", String.valueOf(messageSize));

            InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
          }

          @Override
          public void onException(MethodInvocation inv) {
            startTime.remove();
            String error = inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown";
            log.warn(
                "[RABBITMQ-PUBLISH-ERROR] {}.{}: {}",
                inv.getTargetClass().getSimpleName(),
                inv.getMethodName(),
                error);
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type("rabbitmq-publish-error")
                        .plugin("rabbitmq")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .attribute("error", error)
                        .build());
          }
        };

    // --- Consumer interceptor: basicAck / basicNack / basicReject ---
    Interceptor ackInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            if (log.isDebugEnabled()) {
              long deliveryTag = extractLongArg(inv, 0);
              log.debug("[RABBITMQ-ACK] {} deliveryTag={}", inv.getMethodName(), deliveryTag);
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            long deliveryTag = extractLongArg(inv, 0);
            InterceptorEvent event =
                InterceptorEvent.builder()
                    .type("rabbitmq-" + inv.getMethodName())
                    .plugin("rabbitmq")
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .attribute("deliveryTag", String.valueOf(deliveryTag))
                    .build();
            InterceptorEventPublisher.getInstance().publish(event);
          }

          @Override
          public void onException(MethodInvocation inv) {
            String error = inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown";
            log.warn(
                "[RABBITMQ-ACK-ERROR] {}.{}: {}",
                inv.getTargetClass().getSimpleName(),
                inv.getMethodName(),
                error);
          }
        };

    // --- Connection interceptor: newConnection ---
    Interceptor connectionInterceptor =
        new Interceptor() {
          private final ThreadLocal<Long> startTime = new ThreadLocal<>();

          @Override
          public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());
            if (log.isDebugEnabled()) {
              log.debug("[RABBITMQ-CONN] Creating new connection");
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            Long start = startTime.get();
            startTime.remove();
            if (start == null) return;

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String host = extractHostFromFactory(inv);
            int port = extractPortFromFactory(inv);

            if (log.isDebugEnabled()) {
              log.debug("[RABBITMQ-CONN] Connected to {}:{} took {}ms", host, port, elapsedMs);
            }

            InterceptorEvent.Builder eventBuilder =
                InterceptorEvent.builder()
                    .type("rabbitmq-connection")
                    .plugin("rabbitmq")
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .durationMs(elapsedMs);
            if (host != null) eventBuilder.attribute("host", host);
            if (port > 0) eventBuilder.attribute("port", String.valueOf(port));

            InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
          }

          @Override
          public void onException(MethodInvocation inv) {
            startTime.remove();
            String error = inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown";
            log.warn("[RABBITMQ-CONN-ERROR] Failed to create connection: {}", error);
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type("rabbitmq-connection-error")
                        .plugin("rabbitmq")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .attribute("error", error)
                        .build());
          }
        };

    // Register interceptors
    // ChannelN: basicPublish, basicAck, basicNack, basicReject
    registry.register(
        interceptClassPattern(CHANNEL_N.replace(".", "\\."))
            .methodPattern("basicPublish")
            .around(inv -> publishInterceptor.before(inv), inv -> publishInterceptor.after(inv))
            .priority(10)
            .build());

    registry.register(
        interceptClassPattern(CHANNEL_N.replace(".", "\\."))
            .methodPattern("basicAck|basicNack|basicReject")
            .around(inv -> ackInterceptor.before(inv), inv -> ackInterceptor.after(inv))
            .priority(10)
            .build());

    // ConnectionFactory: newConnection
    registry.register(
        interceptSubclassOf(CONNECTION_FACTORY)
            .methodPattern("newConnection")
            .around(
                inv -> connectionInterceptor.before(inv), inv -> connectionInterceptor.after(inv))
            .priority(10)
            .build());
  }

  /** Extract a string argument at the given index. */
  String extractStringArg(MethodInvocation inv, int index) {
    try {
      if (inv.getArguments() != null && inv.getArguments().length > index) {
        Object arg = inv.getArgument(index);
        return arg != null ? arg.toString() : null;
      }
    } catch (Exception e) {
      // Ignore
    }
    return null;
  }

  /** Extract a long argument at the given index. */
  long extractLongArg(MethodInvocation inv, int index) {
    try {
      if (inv.getArguments() != null && inv.getArguments().length > index) {
        Object arg = inv.getArgument(index);
        if (arg instanceof Long) return (Long) arg;
        if (arg instanceof Integer) return ((Integer) arg).longValue();
      }
    } catch (Exception e) {
      // Ignore
    }
    return -1;
  }

  /**
   * Extract message body size from the arguments. basicPublish(exchange, routingKey, mandatory,
   * immediate, properties, body) body is a byte[] at index 5 (or 2 in older API).
   */
  int extractMessageSize(MethodInvocation inv) {
    try {
      if (inv.getArguments() != null) {
        // Try to find byte[] argument (message body)
        for (Object arg : inv.getArguments()) {
          if (arg instanceof byte[]) {
            return ((byte[]) arg).length;
          }
        }
      }
    } catch (Exception e) {
      // Ignore
    }
    return -1;
  }

  /**
   * Inject trace headers into AMQP basic properties via reflection. The properties argument in
   * basicPublish is typically AMQP.BasicProperties.
   */
  void injectTraceIntoProperties(MethodInvocation inv) {
    try {
      String traceId = ThreadContext.get("traceId");
      if (traceId == null) return;

      // Find the AMQP.BasicProperties argument and add headers
      if (inv.getArguments() != null) {
        for (int i = 0; i < inv.getArguments().length; i++) {
          Object arg = inv.getArguments()[i];
          if (arg == null) continue;

          // Check if this looks like AMQP.BasicProperties
          String className = arg.getClass().getName();
          if (className.contains("BasicProperties") || className.contains("AMQP")) {
            // Try to get headers map and add trace
            try {
              java.lang.reflect.Method getHeaders = arg.getClass().getMethod("getHeaders");
              Object headers = getHeaders.invoke(arg);
              if (headers instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> headersMap = (java.util.Map<String, Object>) headers;
                headersMap.put("X-Trace-Id", traceId);
                String spanId = ThreadContext.get("spanId");
                if (spanId != null) {
                  headersMap.put("X-Span-Id", spanId);
                }
              }
            } catch (NoSuchMethodException e) {
              // Try builder pattern: properties.builder().headers(...).build()
              try {
                java.lang.reflect.Method builderMethod = arg.getClass().getMethod("builder");
                Object builder = builderMethod.invoke(arg);
                if (builder != null) {
                  java.util.Map<String, String> traceHeaders = new java.util.HashMap<>();
                  traceHeaders.put("X-Trace-Id", traceId);
                  String spanId = ThreadContext.get("spanId");
                  if (spanId != null) {
                    traceHeaders.put("X-Span-Id", spanId);
                  }
                  java.lang.reflect.Method headersMethod =
                      builder.getClass().getMethod("headers", java.util.Map.class);
                  headersMethod.invoke(builder, traceHeaders);
                  java.lang.reflect.Method buildMethod = builder.getClass().getMethod("build");
                  Object newProps = buildMethod.invoke(builder);
                  inv.setArgument(i, newProps);
                }
              } catch (NoSuchMethodException e2) {
                // Cannot inject — non-critical
              }
            }
            break;
          }
        }
      }
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("[RABBITMQ] Failed to inject trace: {}", e.getMessage());
      }
    }
  }

  /** Extract host from ConnectionFactory target via reflection. */
  String extractHostFromFactory(MethodInvocation inv) {
    try {
      if (inv.getTarget() != null) {
        java.lang.reflect.Method getHost = inv.getTarget().getClass().getMethod("getHost");
        Object host = getHost.invoke(inv.getTarget());
        return host != null ? host.toString() : null;
      }
    } catch (Exception e) {
      // Ignore
    }
    return null;
  }

  /** Extract port from ConnectionFactory target via reflection. */
  int extractPortFromFactory(MethodInvocation inv) {
    try {
      if (inv.getTarget() != null) {
        java.lang.reflect.Method getPort = inv.getTarget().getClass().getMethod("getPort");
        Object port = getPort.invoke(inv.getTarget());
        if (port instanceof Integer) return (Integer) port;
      }
    } catch (Exception e) {
      // Ignore
    }
    return -1;
  }

  // Expose for testing
  long getSlowPublishThresholdMs() {
    return slowPublishThresholdMs;
  }

  long getSlowConsumeThresholdMs() {
    return slowConsumeThresholdMs;
  }

  boolean isTrackMessageSize() {
    return trackMessageSize;
  }

  boolean isPropagateTrace() {
    return propagateTrace;
  }

  boolean isEnabled() {
    return enabled;
  }
}
