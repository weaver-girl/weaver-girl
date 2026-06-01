package com.github.cc11001100.weavergirl.plugins.httpclient;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client instrumentation plugin.
 * Intercepts Apache HttpClient and OkHttp to:
 * - Log HTTP method, URL, response code
 * - Measure request timing
 * - Detect slow HTTP calls
 * - Propagate traceId via HTTP headers
 *
 * <p>Target classes (string names, no compile dependency):</p>
 * <ul>
 *   <li>{@code org.apache.http.impl.client.CloseableHttpClient} &mdash; execute methods</li>
 *   <li>{@code okhttp3.RealCall} &mdash; execute and enqueue methods</li>
 * </ul>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowThreshold} &mdash; Slow request threshold in ms (default: 3000)</li>
 *   <li>{@code propagateTrace} &mdash; Propagate traceId header (default: true)</li>
 *   <li>{@code traceHeaderName} &mdash; Header name for trace propagation (default: X-Trace-Id)</li>
 *   <li>{@code enabled} &mdash; Enable/disable (default: true)</li>
 * </ul>
 */
public class HttpClientPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(HttpClientPlugin.class);

    private long slowThresholdMs = 3000;
    private boolean propagateTrace = true;
    private String traceHeaderName = "X-Trace-Id";
    private boolean enabled = true;

    // Target class names (as strings, no import dependency)
    private static final String APACHE_HTTP_CLIENT = "org.apache.http.impl.client.CloseableHttpClient";
    private static final String OKHTTP_REAL_CALL = "okhttp3.RealCall";

    @Override
    public String name() {
        return "httpclient";
    }

    @Override
    public void init(PluginContext context) {
        String thresholdStr = context.getConfig("slowThreshold", "3000");
        try { slowThresholdMs = Long.parseLong(thresholdStr); } catch (NumberFormatException e) { slowThresholdMs = 3000; }
        propagateTrace = "true".equalsIgnoreCase(context.getConfig("propagateTrace", "true"));
        traceHeaderName = context.getConfig("traceHeaderName", "X-Trace-Id");
        enabled = "true".equalsIgnoreCase(context.getConfig("enabled", "true"));
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor httpClientInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());

                // Propagate traceId into request headers via reflection
                if (propagateTrace) {
                    String traceId = ThreadContext.get("traceId");
                    if (traceId != null) {
                        injectTraceHeader(inv, traceId);
                    }
                }

                if (log.isDebugEnabled()) {
                    String url = extractUrl(inv);
                    String method = extractHttpMethod(inv);
                    String urlInfo = url != null ? " " + url : "";
                    String methodInfo = method != null ? method : "HTTP";
                    log.debug("[HTTP-CLIENT] {} {} started", methodInfo, urlInfo);
                }
            }

            @Override
            public void after(MethodInvocation inv) {
                Long start = startTime.get();
                startTime.remove();
                if (start != null) {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    String url = extractUrl(inv);
                    String method = extractHttpMethod(inv);
                    int responseCode = extractResponseCode(inv);

                    String urlInfo = url != null ? " " + url : "";
                    String methodInfo = method != null ? method : "HTTP";
                    String codeInfo = responseCode > 0 ? " -> " + responseCode : "";

                    if (elapsedMs >= slowThresholdMs) {
                        log.warn("[SLOW-HTTP] {}{} took {}ms{} (threshold: {}ms)", methodInfo, urlInfo, elapsedMs, codeInfo, slowThresholdMs);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[HTTP-CLIENT] {}{} took {}ms{}", methodInfo, urlInfo, elapsedMs, codeInfo);
                    }
                }
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();
                log.warn("[HTTP-CLIENT-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(),
                        inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown");
            }
        };

        // Intercept Apache HttpClient execute methods
        registry.register(interceptClassPattern(APACHE_HTTP_CLIENT.replace(".", "\\."))
            .methodPattern("execute|doExecute")
            .around(
                inv -> httpClientInterceptor.before(inv),
                inv -> httpClientInterceptor.after(inv)
            )
            .priority(10)
            .build());

        // Intercept OkHttp RealCall execute and enqueue methods
        registry.register(interceptClassPattern(OKHTTP_REAL_CALL.replace(".", "\\."))
            .methodPattern("execute|enqueue")
            .around(
                inv -> httpClientInterceptor.before(inv),
                inv -> httpClientInterceptor.after(inv)
            )
            .priority(10)
            .build());
    }

    /**
     * Try to extract URL from the MethodInvocation via reflection.
     * For Apache HttpClient, the first argument is typically an HttpUriRequest.
     * For OkHttp RealCall, the request is obtained from the target's request() method.
     */
    String extractUrl(MethodInvocation inv) {
        try {
            // Try Apache HttpClient: first argument may be HttpRequest
            if (inv.getArguments() != null && inv.getArguments().length > 0) {
                Object arg = inv.getArgument(0);
                // Try getURI() method (Apache HttpUriRequest)
                try {
                    java.lang.reflect.Method getUriMethod = arg.getClass().getMethod("getURI");
                    Object uri = getUriMethod.invoke(arg);
                    return uri != null ? uri.toString() : null;
                } catch (NoSuchMethodException e) {
                    // Try url() method (OkHttp Request)
                    try {
                        java.lang.reflect.Method urlMethod = arg.getClass().getMethod("url");
                        Object url = urlMethod.invoke(arg);
                        return url != null ? url.toString() : null;
                    } catch (NoSuchMethodException e2) {
                        // Fall through
                    }
                }
            }
            // Try OkHttp RealCall: request() on the target
            if (inv.getTarget() != null) {
                try {
                    java.lang.reflect.Method requestMethod = inv.getTarget().getClass().getMethod("request");
                    Object request = requestMethod.invoke(inv.getTarget());
                    if (request != null) {
                        java.lang.reflect.Method urlMethod = request.getClass().getMethod("url");
                        Object url = urlMethod.invoke(request);
                        return url != null ? url.toString() : null;
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
     * Try to extract HTTP method from the MethodInvocation via reflection.
     */
    String extractHttpMethod(MethodInvocation inv) {
        try {
            if (inv.getArguments() != null && inv.getArguments().length > 0) {
                Object arg = inv.getArgument(0);
                // Try getMethod() (Apache HttpRequest)
                try {
                    java.lang.reflect.Method getMethod = arg.getClass().getMethod("getMethod");
                    Object result = getMethod.invoke(arg);
                    return result != null ? result.toString() : null;
                } catch (NoSuchMethodException e) {
                    // Try method() on OkHttp Request
                    try {
                        java.lang.reflect.Method methodMethod = arg.getClass().getMethod("method");
                        Object result = methodMethod.invoke(arg);
                        return result != null ? result.toString() : null;
                    } catch (NoSuchMethodException e2) {
                        // Fall through
                    }
                }
            }
            // Try OkHttp RealCall: request().method()
            if (inv.getTarget() != null) {
                try {
                    java.lang.reflect.Method requestMethod = inv.getTarget().getClass().getMethod("request");
                    Object request = requestMethod.invoke(inv.getTarget());
                    if (request != null) {
                        java.lang.reflect.Method methodMethod = request.getClass().getMethod("method");
                        Object result = methodMethod.invoke(request);
                        return result != null ? result.toString() : null;
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
     * Try to extract HTTP response code from the return value via reflection.
     */
    int extractResponseCode(MethodInvocation inv) {
        try {
            Object returnValue = inv.getReturnValue();
            if (returnValue != null) {
                // Try getStatusLine().getStatusCode() (Apache HttpResponse)
                try {
                    java.lang.reflect.Method getStatusLine = returnValue.getClass().getMethod("getStatusLine");
                    Object statusLine = getStatusLine.invoke(returnValue);
                    if (statusLine != null) {
                        java.lang.reflect.Method getStatusCode = statusLine.getClass().getMethod("getStatusCode");
                        Object code = getStatusCode.invoke(statusLine);
                        if (code instanceof Integer) return (Integer) code;
                    }
                } catch (NoSuchMethodException e) {
                    // Try code() (OkHttp Response)
                    try {
                        java.lang.reflect.Method codeMethod = returnValue.getClass().getMethod("code");
                        Object code = codeMethod.invoke(returnValue);
                        if (code instanceof Integer) return (Integer) code;
                    } catch (NoSuchMethodException e2) {
                        // Fall through
                    }
                }
            }
        } catch (Exception e) {
            // Reflection failed — ignore
        }
        return 0;
    }

    /**
     * Inject trace header into the HTTP request via reflection.
     * For Apache HttpClient, the first argument (HttpUriRequest) has addHeader().
     * For OkHttp, we cannot easily mutate the Request, but try via builder pattern.
     */
    void injectTraceHeader(MethodInvocation inv, String traceId) {
        try {
            Object arg = inv.getArgument(0);
            // Try addHeader(String, String) (Apache HttpClient)
            try {
                java.lang.reflect.Method addHeader = arg.getClass().getMethod("addHeader", String.class, String.class);
                addHeader.invoke(arg, traceHeaderName, traceId);
                return;
            } catch (NoSuchMethodException e) {
                // Try header(String, String) returning builder (OkHttp Request builder pattern)
                try {
                    java.lang.reflect.Method newBuilder = arg.getClass().getMethod("newBuilder");
                    Object builder = newBuilder.invoke(arg);
                    if (builder != null) {
                        java.lang.reflect.Method headerMethod = builder.getClass().getMethod("header", String.class, String.class);
                        headerMethod.invoke(builder, traceHeaderName, traceId);
                        java.lang.reflect.Method buildMethod = builder.getClass().getMethod("build");
                        Object newRequest = buildMethod.invoke(builder);
                        // Replace first argument with the new request using setArgument
                        // (getArguments() returns a clone, so mutation would be silently discarded)
                        inv.setArgument(0, newRequest);
                    }
                } catch (NoSuchMethodException e2) {
                    // Cannot inject — ignore
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

    boolean isPropagateTrace() {
        return propagateTrace;
    }

    String getTraceHeaderName() {
        return traceHeaderName;
    }

    boolean isEnabled() {
        return enabled;
    }
}
