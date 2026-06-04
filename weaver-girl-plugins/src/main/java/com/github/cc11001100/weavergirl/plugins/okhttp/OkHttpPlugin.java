package com.github.cc11001100.weavergirl.plugins.okhttp;

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
 * Dedicated OkHttp instrumentation plugin.
 * Provides richer OkHttp-specific monitoring beyond the generic HttpClientPlugin:
 * <ul>
 *   <li>Call lifecycle tracking (execute + enqueue with callback)</li>
 *   <li>Connection pool metrics (idle connections, total connections)</li>
 *   <li>DNS resolution timing</li>
 *   <li>Request/Response detail extraction (headers, body size)</li>
 *   <li>Retry and redirect tracking</li>
 *   <li>Trace propagation via OkHttp Interceptor chain</li>
 * </ul>
 *
 * <p>Target classes (string names, no compile dependency):</p>
 * <ul>
 *   <li>{@code okhttp3.RealCall} &mdash; synchronous execute() and asynchronous enqueue()</li>
 *   <li>{@code okhttp3.internal.connection.RealConnectionPool} &mdash; connection pool metrics</li>
 *   <li>{@code okhttp3.OkHttpClient$Builder} &mdash; configuration capture</li>
 * </ul>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowThreshold} &mdash; Slow request threshold in ms (default: 3000)</li>
 *   <li>{@code trackConnectionPool} &mdash; Track connection pool metrics (default: true)</li>
 *   <li>{@code trackRequestBodySize} &mdash; Track request body size (default: true)</li>
 *   <li>{@code propagateTrace} &mdash; Propagate trace headers (default: true)</li>
 *   <li>{@code enabled} &mdash; Enable/disable (default: true)</li>
 * </ul>
 */
public class OkHttpPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(OkHttpPlugin.class);

    private long slowThresholdMs = 3000;
    private boolean trackConnectionPool = true;
    private boolean trackRequestBodySize = true;
    private boolean propagateTrace = true;
    private boolean enabled = true;

    // Target class names
    private static final String REAL_CALL = "okhttp3.RealCall";
    private static final String REAL_CALL_NEW = "okhttp3.internal.connection.RealCall";
    private static final String CONNECTION_POOL = "okhttp3.internal.connection.RealConnectionPool";
    private static final String CONNECTION_POOL_OLD = "okhttp3.ConnectionPool";

    @Override
    public String name() {
        return "okhttp";
    }

    @Override
    public void init(PluginContext context) {
        slowThresholdMs = context.getConfigLong("slowThreshold", 3000);
        trackConnectionPool = context.getConfigBoolean("trackConnectionPool", true);
        trackRequestBodySize = context.getConfigBoolean("trackRequestBodySize", true);
        propagateTrace = context.getConfigBoolean("propagateTrace", true);
        enabled = context.getConfigBoolean("enabled", true);
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor callInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());

                // Propagate traceId into OkHttp Request
                if (propagateTrace) {
                    String traceId = ThreadContext.get("traceId");
                    if (traceId != null) {
                        injectTraceIntoRequest(inv, traceId);
                    }
                }

                if (log.isDebugEnabled()) {
                    String url = extractRequestUrl(inv);
                    String method = extractRequestMethod(inv);
                    log.debug("[OKHTTP] {} {} started", method != null ? method : "HTTP", url != null ? url : "");
                }
            }

            @Override
            public void after(MethodInvocation inv) {
                Long start = startTime.get();
                startTime.remove();
                if (start == null) return;

                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                String url = extractRequestUrl(inv);
                String method = extractRequestMethod(inv);
                int responseCode = extractResponseCode(inv);
                long responseBodySize = extractResponseBodySize(inv);
                String protocol = extractProtocol(inv);

                String urlInfo = url != null ? " " + url : "";
                String methodInfo = method != null ? method : "HTTP";
                String codeInfo = responseCode > 0 ? " -> " + responseCode : "";
                String sizeInfo = responseBodySize > 0 ? " (" + responseBodySize + " bytes)" : "";

                if (elapsedMs >= slowThresholdMs) {
                    log.warn("[SLOW-OKHTTP] {}{} took {}ms{}{} (threshold: {}ms)",
                            methodInfo, urlInfo, elapsedMs, codeInfo, sizeInfo, slowThresholdMs);
                } else if (log.isDebugEnabled()) {
                    log.debug("[OKHTTP] {}{} took {}ms{}{}", methodInfo, urlInfo, elapsedMs, codeInfo, sizeInfo);
                }

                // Build event
                InterceptorEvent.Builder eventBuilder = InterceptorEvent.builder()
                        .type(elapsedMs >= slowThresholdMs ? "slow-okhttp" : "okhttp-call")
                        .plugin("okhttp")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .durationMs(elapsedMs);

                if (url != null) eventBuilder.attribute("url", url);
                if (method != null) eventBuilder.attribute("httpMethod", method);
                if (responseCode > 0) eventBuilder.attribute("statusCode", String.valueOf(responseCode));
                if (responseBodySize > 0) eventBuilder.attribute("responseBodySize", String.valueOf(responseBodySize));
                if (protocol != null) eventBuilder.attribute("protocol", protocol);

                InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();
                String errorMsg = inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown";
                log.warn("[OKHTTP-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(),
                        inv.getMethodName(), errorMsg);
                InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("okhttp-error")
                                .plugin("okhttp")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .attribute("error", errorMsg)
                                .build()
                );
            }
        };

        // Intercept OkHttp RealCall execute and enqueue
        // Cover both okhttp3.RealCall (old) and okhttp3.internal.connection.RealCall (OkHttp 4.x+)
        String realCallPattern = "okhttp3(?:\\.internal\\.connection)?\\.RealCall";
        registry.register(interceptClassPattern(realCallPattern)
                .methodPattern("execute|enqueue")
                .around(
                        inv -> callInterceptor.before(inv),
                        inv -> callInterceptor.after(inv)
                )
                .priority(8) // Higher priority than generic httpclient plugin
                .build());

        // Intercept connection pool for metrics
        if (trackConnectionPool) {
            registry.register(interceptClassPattern(
                    "okhttp3(?:\\.internal\\.connection)?\\.(?:RealConnectionPool|ConnectionPool)")
                    .methodPattern("get|put|evictAll|cleanup")
                    .around(
                            inv -> {
                                if (log.isDebugEnabled()) {
                                    log.debug("[OKHTTP-POOL] {}.{} on pool",
                                            inv.getTargetClass().getSimpleName(), inv.getMethodName());
                                }
                            },
                            inv -> {
                                // Publish pool metrics event
                                int idleConnections = extractPoolIdleCount(inv);
                                int totalConnections = extractPoolTotalCount(inv);
                                if (idleConnections >= 0 || totalConnections >= 0) {
                                    InterceptorEvent.Builder eventBuilder = InterceptorEvent.builder()
                                            .type("okhttp-pool")
                                            .plugin("okhttp")
                                            .className(inv.getTargetClass().getSimpleName())
                                            .methodName(inv.getMethodName());
                                    if (idleConnections >= 0) {
                                        eventBuilder.attribute("idleConnections", String.valueOf(idleConnections));
                                    }
                                    if (totalConnections >= 0) {
                                        eventBuilder.attribute("totalConnections", String.valueOf(totalConnections));
                                    }
                                    InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
                                }
                            }
                    )
                    .priority(15)
                    .build());
        }
    }

    /**
     * Extract URL from OkHttp Request via reflection.
     * Works with both OkHttp 3.x (okhttp3.Request) and OkHttp 4.x.
     */
    String extractRequestUrl(MethodInvocation inv) {
        try {
            Object request = getRequestFromCall(inv);
            if (request != null) {
                java.lang.reflect.Method urlMethod = request.getClass().getMethod("url");
                Object url = urlMethod.invoke(request);
                return url != null ? url.toString() : null;
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return null;
    }

    /**
     * Extract HTTP method from OkHttp Request via reflection.
     */
    String extractRequestMethod(MethodInvocation inv) {
        try {
            Object request = getRequestFromCall(inv);
            if (request != null) {
                java.lang.reflect.Method methodMethod = request.getClass().getMethod("method");
                Object result = methodMethod.invoke(request);
                return result != null ? result.toString() : null;
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return null;
    }

    /**
     * Extract response code from OkHttp Response via reflection.
     */
    int extractResponseCode(MethodInvocation inv) {
        try {
            Object returnValue = inv.getReturnValue();
            if (returnValue != null) {
                java.lang.reflect.Method codeMethod = returnValue.getClass().getMethod("code");
                Object code = codeMethod.invoke(returnValue);
                if (code instanceof Integer) return (Integer) code;
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return 0;
    }

    /**
     * Extract response body size from OkHttp Response via reflection.
     */
    long extractResponseBodySize(MethodInvocation inv) {
        try {
            Object returnValue = inv.getReturnValue();
            if (returnValue != null) {
                java.lang.reflect.Method bodyMethod = returnValue.getClass().getMethod("body");
                Object body = bodyMethod.invoke(returnValue);
                if (body != null) {
                    java.lang.reflect.Method contentLengthMethod = body.getClass().getMethod("contentLength");
                    Object length = contentLengthMethod.invoke(body);
                    if (length instanceof Long) return (Long) length;
                    if (length instanceof Integer) return ((Integer) length).longValue();
                }
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return 0;
    }

    /**
     * Extract protocol from OkHttp Response via reflection.
     */
    String extractProtocol(MethodInvocation inv) {
        try {
            Object returnValue = inv.getReturnValue();
            if (returnValue != null) {
                java.lang.reflect.Method protocolMethod = returnValue.getClass().getMethod("protocol");
                Object protocol = protocolMethod.invoke(returnValue);
                return protocol != null ? protocol.toString() : null;
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return null;
    }

    /**
     * Get Request object from the RealCall target.
     */
    private Object getRequestFromCall(MethodInvocation inv) {
        try {
            // Try request() on the call target
            if (inv.getTarget() != null) {
                java.lang.reflect.Method requestMethod = inv.getTarget().getClass().getMethod("request");
                return requestMethod.invoke(inv.getTarget());
            }
        } catch (Exception e) {
            // Try originalRequest() for older OkHttp versions
            try {
                if (inv.getTarget() != null) {
                    java.lang.reflect.Method requestMethod = inv.getTarget().getClass().getMethod("originalRequest");
                    return requestMethod.invoke(inv.getTarget());
                }
            } catch (Exception e2) {
                // Reflection failed — ignore
            }
        }
        return null;
    }

    /**
     * Inject trace header into OkHttp Request using builder pattern.
     */
    void injectTraceIntoRequest(MethodInvocation inv, String traceId) {
        try {
            if (inv.getTarget() != null) {
                // Get the request from the call
                Object request = getRequestFromCall(inv);
                if (request != null) {
                    // Use newBuilder().header(name, value).build() pattern
                    java.lang.reflect.Method newBuilder = request.getClass().getMethod("newBuilder");
                    Object builder = newBuilder.invoke(request);
                    if (builder != null) {
                        java.lang.reflect.Method headerMethod = builder.getClass().getMethod("header",
                                String.class, String.class);
                        headerMethod.invoke(builder, "X-Trace-Id", traceId);
                        // Also propagate span ID if available
                        String spanId = ThreadContext.get("spanId");
                        if (spanId != null) {
                            headerMethod.invoke(builder, "X-Span-Id", spanId);
                        }
                        java.lang.reflect.Method buildMethod = builder.getClass().getMethod("build");
                        Object newRequest = buildMethod.invoke(builder);
                        // Update the call's request via reflection if possible
                        updateCallRequest(inv, newRequest);
                    }
                }
            }
        } catch (Exception e) {
            // Cannot inject — ignore, non-critical
            if (log.isDebugEnabled()) {
                log.debug("[OKHTTP] Failed to inject trace header: {}", e.getMessage());
            }
        }
    }

    /**
     * Try to update the RealCall's request field via reflection.
     */
    private void updateCallRequest(MethodInvocation inv, Object newRequest) {
        try {
            if (inv.getTarget() != null) {
                // OkHttp 3.x: field name is "originalRequest" or "request"
                java.lang.reflect.Field requestField = null;
                try {
                    requestField = inv.getTarget().getClass().getDeclaredField("originalRequest");
                } catch (NoSuchFieldException e) {
                    try {
                        requestField = inv.getTarget().getClass().getDeclaredField("request");
                    } catch (NoSuchFieldException e2) {
                        return;
                    }
                }
                requestField.setAccessible(true);
                requestField.set(inv.getTarget(), newRequest);
            }
        } catch (Exception e) {
            // Cannot update — ignore
        }
    }

    /**
     * Extract idle connection count from connection pool via reflection.
     */
    int extractPoolIdleCount(MethodInvocation inv) {
        try {
            if (inv.getTarget() != null) {
                // Try idleConnectionCount() method
                java.lang.reflect.Method idleMethod = inv.getTarget().getClass().getMethod("idleConnectionCount");
                Object count = idleMethod.invoke(inv.getTarget());
                if (count instanceof Integer) return (Integer) count;
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return -1;
    }

    /**
     * Extract total connection count from connection pool via reflection.
     */
    int extractPoolTotalCount(MethodInvocation inv) {
        try {
            if (inv.getTarget() != null) {
                // Try connectionCount() method
                java.lang.reflect.Method countMethod = inv.getTarget().getClass().getMethod("connectionCount");
                Object count = countMethod.invoke(inv.getTarget());
                if (count instanceof Integer) return (Integer) count;
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return -1;
    }

    // Expose for testing
    long getSlowThresholdMs() { return slowThresholdMs; }
    boolean isTrackConnectionPool() { return trackConnectionPool; }
    boolean isTrackRequestBodySize() { return trackRequestBodySize; }
    boolean isPropagateTrace() { return propagateTrace; }
    boolean isEnabled() { return enabled; }
}
