package com.github.cc11001100.weavergirl.plugins.kafka;

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
 * Kafka instrumentation plugin.
 * Intercepts Kafka producer and consumer to:
 * - Measure timing for producer send and consumer poll
 * - Extract topic name from arguments via reflection
 * - Detect slow operations
 * - Propagate traceId in Kafka headers
 *
 * <p>Target classes (string names, no compile dependency):</p>
 * <ul>
 *   <li>{@code org.apache.kafka.clients.producer.KafkaProducer} &mdash; send method</li>
 *   <li>{@code org.apache.kafka.clients.consumer.KafkaConsumer} &mdash; poll method</li>
 * </ul>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowThreshold} &mdash; Slow operation threshold in ms (default: 1000)</li>
 *   <li>{@code logTopic} &mdash; Log topic name (default: true)</li>
 *   <li>{@code enabled} &mdash; Enable/disable (default: true)</li>
 * </ul>
 */
public class KafkaPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(KafkaPlugin.class);

    private long slowThresholdMs = 1000;
    private boolean logTopic = true;
    private boolean enabled = true;

    // Target class names (as strings, no import dependency)
    private static final String KAFKA_PRODUCER = "org.apache.kafka.clients.producer.KafkaProducer";
    private static final String KAFKA_CONSUMER = "org.apache.kafka.clients.consumer.KafkaConsumer";

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void init(PluginContext context) {
        slowThresholdMs = context.getConfigLong("slowThreshold", 1000);
        logTopic = context.getConfigBoolean("logTopic", true);
        enabled = context.getConfigBoolean("enabled", true);
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor producerInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());

                // Propagate traceId in Kafka record headers via reflection
                String traceId = ThreadContext.get("traceId");
                if (traceId != null) {
                    injectTraceHeader(inv, traceId);
                }

                if (log.isDebugEnabled()) {
                    String topic = extractTopic(inv);
                    String topicInfo = (logTopic && topic != null) ? " topic=" + topic : "";
                    log.debug("[KAFKA-PRODUCER] send{}", topicInfo);
                }
            }

            @Override
            public void after(MethodInvocation inv) {
                Long start = startTime.get();
                startTime.remove();
                if (start != null) {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    String topic = extractTopic(inv);
                    String topicInfo = (logTopic && topic != null) ? " topic=" + topic : "";

                    if (elapsedMs >= slowThresholdMs) {
                        log.warn("[SLOW-KAFKA-PRODUCER] send took {}ms{} (threshold: {}ms)", elapsedMs, topicInfo, slowThresholdMs);
                        InterceptorEvent.Builder eventBuilder = InterceptorEvent.builder()
                                .type("slow-kafka")
                                .plugin("kafka")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .durationMs(elapsedMs);
                        if (topic != null) {
                            eventBuilder.attribute("topic", topic);
                        }
                        InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
                    } else if (log.isDebugEnabled()) {
                        log.debug("[KAFKA-PRODUCER] send took {}ms{}", elapsedMs, topicInfo);
                    }
                }
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();
                log.warn("[KAFKA-PRODUCER-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(),
                        inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown");
                InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("kafka-error")
                                .plugin("kafka")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .attribute("error", inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown")
                                .build()
                );
            }
        };

        Interceptor consumerInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());
            }

            @Override
            public void after(MethodInvocation inv) {
                Long start = startTime.get();
                startTime.remove();
                if (start != null) {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    // Try to extract topic from returned ConsumerRecords
                    String topic = extractTopicFromConsumerRecords(inv);
                    String topicInfo = (logTopic && topic != null) ? " topic=" + topic : "";

                    if (elapsedMs >= slowThresholdMs) {
                        log.warn("[SLOW-KAFKA-CONSUMER] poll took {}ms{} (threshold: {}ms)", elapsedMs, topicInfo, slowThresholdMs);
                        InterceptorEvent.Builder eventBuilder = InterceptorEvent.builder()
                                .type("slow-kafka")
                                .plugin("kafka")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .durationMs(elapsedMs);
                        if (topic != null) {
                            eventBuilder.attribute("topic", topic);
                        }
                        InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
                    } else if (log.isDebugEnabled()) {
                        log.debug("[KAFKA-CONSUMER] poll took {}ms{}", elapsedMs, topicInfo);
                    }
                }
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();
                log.warn("[KAFKA-CONSUMER-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(),
                        inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown");
                InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("kafka-error")
                                .plugin("kafka")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .attribute("error", inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown")
                                .build()
                );
            }
        };

        // Intercept KafkaProducer.send
        registry.register(interceptClassPattern(KAFKA_PRODUCER.replace(".", "\\."))
            .method("send")
            .around(
                inv -> producerInterceptor.before(inv),
                inv -> producerInterceptor.after(inv)
            )
            .priority(10)
            .build());

        // Intercept KafkaConsumer.poll
        registry.register(interceptClassPattern(KAFKA_CONSUMER.replace(".", "\\."))
            .method("poll")
            .around(
                inv -> consumerInterceptor.before(inv),
                inv -> consumerInterceptor.after(inv)
            )
            .priority(10)
            .build());
    }

    /**
     * Try to extract topic name from the first argument via reflection.
     * For KafkaProducer.send(), the first argument is a ProducerRecord which has topic().
     */
    String extractTopic(MethodInvocation inv) {
        try {
            Object[] args = inv.getArguments();
            if (args != null && args.length > 0) {
                Object arg = args[0];
                // Try topic() method (ProducerRecord)
                try {
                    java.lang.reflect.Method topicMethod = arg.getClass().getMethod("topic");
                    Object topic = topicMethod.invoke(arg);
                    if (topic != null) return topic.toString();
                } catch (NoSuchMethodException e) {
                    // Fall through
                }
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return null;
    }

    /**
     * Try to extract topic name from ConsumerRecords in the return value via reflection.
     * ConsumerRecords implements Iterable and each ConsumerRecord has a topic() method.
     * We take the topic from the first record if available.
     */
    String extractTopicFromConsumerRecords(MethodInvocation inv) {
        try {
            Object returnValue = inv.getReturnValue();
            if (returnValue != null) {
                // Try to iterate and get first record's topic
                try {
                    java.lang.reflect.Method iteratorMethod = returnValue.getClass().getMethod("iterator");
                    Object iterator = iteratorMethod.invoke(returnValue);
                    if (iterator != null) {
                        java.lang.reflect.Method hasNextMethod = iterator.getClass().getMethod("hasNext");
                        Boolean hasNext = (Boolean) hasNextMethod.invoke(iterator);
                        if (hasNext != null && hasNext) {
                            java.lang.reflect.Method nextMethod = iterator.getClass().getMethod("next");
                            Object record = nextMethod.invoke(iterator);
                            if (record != null) {
                                java.lang.reflect.Method topicMethod = record.getClass().getMethod("topic");
                                Object topic = topicMethod.invoke(record);
                                if (topic != null) return topic.toString();
                            }
                        }
                    }
                } catch (NoSuchMethodException e) {
                    // Fall through
                }
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return null;
    }

    /**
     * Inject traceId into Kafka record headers via reflection.
     * For KafkaProducer.send(), the first argument is a ProducerRecord which has headers().
     * We add the trace header to the record's headers.
     */
    void injectTraceHeader(MethodInvocation inv, String traceId) {
        try {
            Object[] args = inv.getArguments();
            if (args != null && args.length > 0) {
                Object arg = args[0];
                // Try headers() method (ProducerRecord)
                try {
                    java.lang.reflect.Method headersMethod = arg.getClass().getMethod("headers");
                    Object headers = headersMethod.invoke(arg);
                    if (headers != null) {
                        // Try add(String, byte[]) method (Headers interface)
                        java.lang.reflect.Method addMethod = headers.getClass().getMethod("add", String.class, byte[].class);
                        addMethod.invoke(headers, "X-Trace-Id", traceId.getBytes("UTF-8"));
                    }
                } catch (NoSuchMethodException e) {
                    // Fall through
                }
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
    }

    // Expose for testing
    long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    boolean isLogTopic() {
        return logTopic;
    }

    boolean isEnabled() {
        return enabled;
    }
}
