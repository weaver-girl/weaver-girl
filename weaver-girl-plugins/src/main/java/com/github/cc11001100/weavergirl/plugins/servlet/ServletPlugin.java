package com.github.cc11001100.weavergirl.plugins.servlet;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Servlet instrumentation plugin with cross-service HTTP trace context propagation.
 * Intercepts HttpServlet.service() and Filter.doFilter() to:
 * - Extract trace context from incoming HTTP request headers (or start a new root span)
 * - Inject trace context into outgoing HTTP response headers
 * - Log HTTP method, URL, query string
 * - Measure request processing time
 * - Store traceId in request attribute for downstream use
 * - Detect slow requests
 *
 * <p>Trace propagation uses the {@link Tracer} API with custom headers:
 * X-Trace-Id, X-Span-Id, X-Parent-Span-Id, X-Sampled, X-Baggage-*.</p>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowThreshold} — Slow request threshold in ms (default: 5000)</li>
 *   <li>{@code logHeaders} — Whether to log request headers (default: false)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 *
 * <p>This plugin uses ClassMatcher.bySuperClass() for HttpServlet and
 * ClassMatcher.byInterface() for Filter, so it matches concrete implementations
 * (e.g., FrameworkServlet, Spring's DispatcherServlet), not just the abstract
 * class itself. This is critical because real applications never instantiate
 * HttpServlet directly.</p>
 */
public class ServletPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(ServletPlugin.class);

    private long slowThresholdMs = 5000;
    private boolean logHeaders = false;
    private boolean enabled = true;

    // Target class names (as strings, no import dependency)
    private static final String HTTP_SERVLET = "javax.servlet.http.HttpServlet";
    private static final String FILTER = "javax.servlet.Filter";
    // Also support Jakarta namespace
    private static final String JAKARTA_HTTP_SERVLET = "jakarta.servlet.http.HttpServlet";
    private static final String JAKARTA_FILTER = "jakarta.servlet.Filter";

    @Override
    public String name() {
        return "servlet";
    }

    @Override
    public void init(PluginContext context) {
        slowThresholdMs = context.getConfigLong("slowThreshold", 5000);
        logHeaders = context.getConfigBoolean("logHeaders", false);
        enabled = context.getConfigBoolean("enabled", true);
    }

    /**
     * Extract HTTP headers from a servlet request object via reflection.
     * Works with both javax.servlet and jakarta.servlet request objects
     * since the servlet API is not a compile dependency.
     *
     * @param request the servlet request object
     * @return map of header name to header value
     */
    static Map<String, String> extractHeadersFromRequest(Object request) {
        Map<String, String> headers = new HashMap<>();
        if (request == null) return headers;
        try {
            Method getHeaderNamesMethod = request.getClass().getMethod("getHeaderNames");
            @SuppressWarnings("unchecked")
            Enumeration<String> headerNames = (Enumeration<String>) getHeaderNamesMethod.invoke(request);
            Method getHeaderMethod = request.getClass().getMethod("getHeader", String.class);
            while (headerNames != null && headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                String value = (String) getHeaderMethod.invoke(request, name);
                if (name != null && value != null) {
                    headers.put(name, value);
                }
            }
        } catch (Exception e) {
            // Not a valid servlet request or reflection failed — return empty map
        }
        return headers;
    }

    /**
     * Inject trace headers into a servlet response object via reflection.
     * Works with both javax.servlet and jakarta.servlet response objects
     * since the servlet API is not a compile dependency.
     *
     * @param response the servlet response object
     * @param headers  the headers to inject
     */
    static void injectHeadersIntoResponse(Object response, Map<String, String> headers) {
        if (response == null || headers == null) return;
        try {
            Method setHeaderMethod = response.getClass().getMethod("setHeader", String.class, String.class);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                setHeaderMethod.invoke(response, entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            // Not a valid servlet response or reflection failed — ignore
        }
    }

    /**
     * Get the servlet request object from the MethodInvocation.
     * For HttpServlet.service(), the target IS the servlet; we call getRequest() on it.
     * For Filter.doFilter(), the request is the first argument.
     *
     * @param inv the method invocation
     * @return the request object, or null if not available
     */
    static Object getRequestObject(MethodInvocation inv) {
        if (inv == null) return null;
        Object[] args = inv.getArguments();
        // Filter.doFilter(request, response, chain) — request is args[0]
        if (args != null && args.length > 0) {
            Object firstArg = args[0];
            if (firstArg != null) {
                // Check if it looks like a servlet request
                try {
                    firstArg.getClass().getMethod("getHeaderNames");
                    return firstArg;
                } catch (NoSuchMethodException e) {
                    // Not a request object — fall through
                }
            }
        }
        // HttpServlet.service() — the target has getRequest()
        if (inv.getTarget() != null) {
            try {
                Method getReqMethod = inv.getTarget().getClass().getMethod("getRequest");
                return getReqMethod.invoke(inv.getTarget());
            } catch (Exception e) {
                // Not an HttpServlet — ignore
            }
        }
        return null;
    }

    /**
     * Get the servlet response object from the MethodInvocation.
     * For Filter.doFilter(), the response is the second argument.
     * For HttpServlet.service(), we get it from the target's getResponse().
     *
     * @param inv the method invocation
     * @return the response object, or null if not available
     */
    static Object getResponseObject(MethodInvocation inv) {
        if (inv == null) return null;
        Object[] args = inv.getArguments();
        // Filter.doFilter(request, response, chain) — response is args[1]
        if (args != null && args.length > 1) {
            Object secondArg = args[1];
            if (secondArg != null) {
                try {
                    secondArg.getClass().getMethod("setHeader", String.class, String.class);
                    return secondArg;
                } catch (NoSuchMethodException e) {
                    // Not a response object — fall through
                }
            }
        }
        // HttpServlet.service() — try getResponse() on target
        if (inv.getTarget() != null) {
            try {
                Method getRespMethod = inv.getTarget().getClass().getMethod("getResponse");
                return getRespMethod.invoke(inv.getTarget());
            } catch (Exception e) {
                // Not an HttpServlet — ignore
            }
        }
        return null;
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor serviceInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());

                // === Trace context propagation: incoming request ===
                // Extract trace context from HTTP request headers
                Object request = getRequestObject(inv);
                if (request != null) {
                    Map<String, String> headerMap = extractHeadersFromRequest(request);
                    SpanContext extracted = Tracer.extract(headerMap);
                    if (extracted != null) {
                        // Continue an existing trace from upstream service
                        Tracer.setCurrentSpan(extracted);
                        if (log.isDebugEnabled()) {
                            log.debug("[SERVLET-TRACE] Continued trace: traceId={}, spanId={}",
                                    extracted.getTraceId(), extracted.getSpanId());
                        }
                    } else {
                        // No trace headers — start a new root span
                        SpanContext newSpan = Tracer.startSpan();
                        if (log.isDebugEnabled()) {
                            log.debug("[SERVLET-TRACE] Started new root span: traceId={}, spanId={}",
                                    newSpan.getTraceId(), newSpan.getSpanId());
                        }
                    }
                } else {
                    // No request object available — start a new root span
                    Tracer.startSpan();
                }

                // Store traceId in ThreadContext and request attribute for downstream use
                SpanContext currentSpan = Tracer.getCurrentSpan();
                if (currentSpan != null) {
                    com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", currentSpan.getTraceId());
                    if (request != null) {
                        try {
                            Method setAttrMethod = request.getClass().getMethod("setAttribute", String.class, Object.class);
                            setAttrMethod.invoke(request, "weavergirl.traceId", currentSpan.getTraceId());
                        } catch (Exception e) {
                            // Not a servlet request — ignore
                        }
                    }
                }

                if (log.isDebugEnabled()) {
                    log.debug("Servlet request started: {}.{}", inv.getTargetClass().getSimpleName(), inv.getMethodName());
                }
            }

            @Override
            public void after(MethodInvocation inv) {
                long elapsedMs = (System.nanoTime() - startTime.get()) / 1_000_000;
                startTime.remove();

                // === Trace context propagation: outgoing response ===
                SpanContext currentSpan = Tracer.getCurrentSpan();
                if (currentSpan != null) {
                    Object response = getResponseObject(inv);
                    if (response != null) {
                        Map<String, String> headerMap = new HashMap<>();
                        Tracer.inject(currentSpan, headerMap);
                        injectHeadersIntoResponse(response, headerMap);
                        if (log.isDebugEnabled()) {
                            log.debug("[SERVLET-TRACE] Injected trace headers into response: traceId={}", currentSpan.getTraceId());
                        }
                    }
                }

                // End the span
                Tracer.endSpan("servlet", "OK");

                // Extract request info via reflection (no compile dependency)
                String method = "UNKNOWN";
                String uri = "/";
                try {
                    Object request = getRequestObject(inv);
                    if (request != null) {
                        method = (String) request.getClass().getMethod("getMethod").invoke(request);
                        uri = (String) request.getClass().getMethod("getRequestURI").invoke(request);
                    }
                } catch (Exception e) {
                    // Fall back to defaults
                }

                if (elapsedMs >= slowThresholdMs) {
                    log.warn("[SLOW-SERVLET] {} {} took {}ms (threshold: {}ms)", method, uri, elapsedMs, slowThresholdMs);
                } else {
                    log.info("[SERVLET] {} {} took {}ms", method, uri, elapsedMs);
                }
                // Publish structured event
                com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type(elapsedMs >= slowThresholdMs ? "slow-request" : "request")
                                .plugin("servlet")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .durationMs(elapsedMs)
                                .attribute("httpMethod", method)
                                .attribute("uri", uri)
                                .build()
                );
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();

                // === Trace context propagation: outgoing response (error) ===
                SpanContext currentSpan = Tracer.getCurrentSpan();
                if (currentSpan != null) {
                    Object response = getResponseObject(inv);
                    if (response != null) {
                        Map<String, String> headerMap = new HashMap<>();
                        Tracer.inject(currentSpan, headerMap);
                        injectHeadersIntoResponse(response, headerMap);
                    }
                }

                // End the span as errored
                String errorMsg = inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown";
                Tracer.endSpan("servlet", "ERROR");

                log.warn("[SERVLET-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(), errorMsg);
                com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("request-error")
                                .plugin("servlet")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .attribute("error", errorMsg)
                                .build()
                );
            }
        };

        // Register for javax.servlet namespace
        // Use bySuperClass for HttpServlet — matches concrete subclasses (FrameworkServlet, etc.)
        registry.register(new InterceptorDefinition(
            name() + "-" + HTTP_SERVLET + "-service",
            new Pointcut(ClassMatcher.bySuperClass(HTTP_SERVLET), MethodMatcher.byName("service")),
            serviceInterceptor, 10
        ));

        // Use byInterface for Filter — matches all Filter implementations
        registry.register(new InterceptorDefinition(
            name() + "-" + FILTER + "-doFilter",
            new Pointcut(ClassMatcher.byInterface(FILTER), MethodMatcher.byName("doFilter")),
            serviceInterceptor, 10
        ));

        // Register for jakarta.servlet namespace
        registry.register(new InterceptorDefinition(
            name() + "-" + JAKARTA_HTTP_SERVLET + "-service",
            new Pointcut(ClassMatcher.bySuperClass(JAKARTA_HTTP_SERVLET), MethodMatcher.byName("service")),
            serviceInterceptor, 10
        ));

        registry.register(new InterceptorDefinition(
            name() + "-" + JAKARTA_FILTER + "-doFilter",
            new Pointcut(ClassMatcher.byInterface(JAKARTA_FILTER), MethodMatcher.byName("doFilter")),
            serviceInterceptor, 10
        ));
    }
}
