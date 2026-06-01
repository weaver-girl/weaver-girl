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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet instrumentation plugin.
 * Intercepts HttpServlet.service() and Filter.doFilter() to:
 * - Log HTTP method, URL, query string
 * - Measure request processing time
 * - Store traceId in request attribute for downstream use
 * - Detect slow requests
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

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor serviceInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());
                // Store traceId in ThreadContext for downstream use
                String traceId = (String) com.github.cc11001100.weavergirl.api.context.ThreadContext.get("traceId");
                if (traceId != null && inv.getTarget() != null) {
                    try {
                        // Set request attribute via reflection (no compile dependency)
                        java.lang.reflect.Method getReqMethod = inv.getTarget().getClass().getMethod("getRequest");
                        Object request = getReqMethod.invoke(inv.getTarget());
                        java.lang.reflect.Method setAttrMethod = request.getClass().getMethod("setAttribute", String.class, Object.class);
                        setAttrMethod.invoke(request, "weavergirl.traceId", traceId);
                    } catch (Exception e) {
                        // Not a servlet request — ignore
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

                // Extract request info via reflection (no compile dependency)
                String method = "UNKNOWN";
                String uri = "/";
                try {
                    if (inv.getTarget() != null) {
                        java.lang.reflect.Method getReqMethod = inv.getTarget().getClass().getMethod("getRequest");
                        Object request = getReqMethod.invoke(inv.getTarget());
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
                log.warn("[SERVLET-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(), inv.getThrowable().getMessage());
                com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("request-error")
                                .plugin("servlet")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .attribute("error", inv.getThrowable().getMessage())
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
