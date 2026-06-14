package com.github.cc11001100.weavergirl.plugins.mongo;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB driver instrumentation plugin.
 * Intercepts MongoDB Java driver internal classes to:
 * - Measure query execution time
 * - Detect slow queries (configurable threshold)
 * - Log collection name and operation type
 * - Extract document previews from arguments
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowThreshold} — Slow query threshold in ms (default: 500)</li>
 *   <li>{@code logCollection} — Whether to log collection name (default: true)</li>
 *   <li>{@code maxDocLength} — Max document preview length to log (default: 100)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 */
public class MongoPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(MongoPlugin.class);

    private long slowThresholdMs = 500;
    private boolean logCollection = true;
    private int maxDocLength = 100;
    private boolean enabled = true;

    // Target class names (MongoDB driver internals)
    private static final String MONGO_CLIENT = "com.mongodb.client.internal.MongoClientImpl";
    private static final String MONGO_COLLECTION = "com.mongodb.client.internal.MongoCollectionImpl";
    private static final String MONGO_DATABASE = "com.mongodb.client.internal.MongoDatabaseImpl";

    @Override
    public String name() {
        return "mongo";
    }

    @Override
    public void init(PluginContext context) {
        slowThresholdMs = context.getConfigLong("slowThreshold", 500);
        logCollection = context.getConfigBoolean("logCollection", true);
        maxDocLength = context.getConfigInt("maxDocLength", 100);
        enabled = context.getConfigBoolean("enabled", true);
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled) return;

        // Shared interceptor for all MongoDB operations — measures execution time
        Interceptor mongoInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());
                if (log.isDebugEnabled()) {
                    String collectionInfo = logCollection ? extractCollectionLabel(inv) : "";
                    String docPreview = extractDocPreview(inv);
                    log.debug("[MONGO] {}.{}{}{}", inv.getTargetClass().getSimpleName(), inv.getMethodName(),
                        collectionInfo, docPreview != null ? " doc=" + docPreview : "");
                }
            }

            @Override
            public void after(MethodInvocation inv) {
                Long start = startTime.get();
                startTime.remove();
                if (start != null) {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    if (elapsedMs >= slowThresholdMs) {
                        String collectionInfo = logCollection ? extractCollectionLabel(inv) : "";
                        String docPreview = extractDocPreview(inv);
                        log.warn("[SLOW-MONGO] {}.{} took {}ms{}{}", inv.getTargetClass().getSimpleName(), inv.getMethodName(),
                            elapsedMs, collectionInfo, docPreview != null ? " doc=" + docPreview : "");
                        InterceptorEvent.Builder eventBuilder = InterceptorEvent.builder()
                                .type("slow-mongo")
                                .plugin("mongo")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .durationMs(elapsedMs)
                                .attribute("operation", inv.getMethodName());
                        if (docPreview != null) {
                            eventBuilder.attribute("doc", docPreview);
                        }
                        InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
                    } else if (log.isDebugEnabled()) {
                        log.debug("[MONGO] {}.{} took {}ms", inv.getTargetClass().getSimpleName(), inv.getMethodName(), elapsedMs);
                    }
                }
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();
                log.warn("[MONGO-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(),
                    inv.getThrowable().getMessage());
                InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("mongo-error")
                                .plugin("mongo")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .attribute("error", inv.getThrowable().getMessage())
                                .build()
                );
            }
        };

        // MongoClientImpl — startSession
        registry.register(interceptClassPattern(MONGO_CLIENT.replace(".", "\\.") + "$")
            .methodPattern("startSession")
            .around(
                inv -> mongoInterceptor.before(inv),
                inv -> mongoInterceptor.after(inv)
            )
            .priority(10)
            .build());

        // MongoCollectionImpl — CRUD operations
        registry.register(interceptClassPattern(MONGO_COLLECTION.replace(".", "\\.") + "$")
            .methodPattern("find|insertOne|insertMany|updateOne|updateMany|deleteOne|deleteMany|replaceOne|countDocuments|aggregate")
            .around(
                inv -> mongoInterceptor.before(inv),
                inv -> mongoInterceptor.after(inv)
            )
            .priority(10)
            .build());

        // MongoDatabaseImpl — createCollection, runCommand
        registry.register(interceptClassPattern(MONGO_DATABASE.replace(".", "\\.") + "$")
            .methodPattern("createCollection|runCommand")
            .around(
                inv -> mongoInterceptor.before(inv),
                inv -> mongoInterceptor.after(inv)
            )
            .priority(10)
            .build());
    }

    /**
     * Extract collection name label from the target class and method.
     * For MongoCollectionImpl, the simple class name contains "MongoCollection".
     * For MongoDatabaseImpl, the operation is on the database level.
     */
    String extractCollectionLabel(MethodInvocation inv) {
        String className = inv.getTargetClass().getSimpleName();
        String methodName = inv.getMethodName();
        // MongoCollectionImpl operations — label with operation type
        if (className.contains("MongoCollection")) {
            return " op=" + methodName;
        }
        // MongoDatabaseImpl operations
        if (className.contains("MongoDatabase")) {
            return " db-op=" + methodName;
        }
        // MongoClientImpl operations
        if (className.contains("MongoClient")) {
            return " client-op=" + methodName;
        }
        return "";
    }

    /**
     * Extract a document preview from the first argument via toString(), truncated.
     */
    String extractDocPreview(MethodInvocation inv) {
        try {
            Object[] args = inv.getArguments();
            if (args != null && args.length > 0 && args[0] != null) {
                String docStr = args[0].toString();
                return truncate(docStr, maxDocLength);
            }
        } catch (Exception e) {
            // Ignore — toString() may throw
        }
        return null;
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // Expose for testing
    long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    boolean isLogCollection() {
        return logCollection;
    }

    int getMaxDocLength() {
        return maxDocLength;
    }

    boolean isEnabled() {
        return enabled;
    }
}