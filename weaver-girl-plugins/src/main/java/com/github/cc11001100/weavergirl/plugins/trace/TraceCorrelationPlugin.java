package com.github.cc11001100.weavergirl.plugins.trace;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace correlation plugin.
 * Generates a unique traceId at entry points and propagates it via ThreadContext.
 * Integrates with SLF4J MDC for automatic traceId inclusion in log messages.
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code headerName} — HTTP header name for trace propagation (default: X-Trace-Id)</li>
 *   <li>{@code entryPointPattern} — Regex for entry point class names (default: ".*Servlet$|.*Controller$|.*Filter$")</li>
 *   <li>{@code mdcKey} — MDC key name for traceId (default: traceId)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 */
public class TraceCorrelationPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(TraceCorrelationPlugin.class);

    private static final AtomicLong traceCounter = new AtomicLong(0);

    private String headerName = "X-Trace-Id";
    private String entryPointPattern = ".*Servlet$|.*Controller$|.*Filter$";
    private String mdcKey = "traceId";
    private boolean enabled = true;

    @Override
    public String name() {
        return "trace-correlation";
    }

    @Override
    public void init(PluginContext context) {
        headerName = context.getConfig("headerName", "X-Trace-Id");
        entryPointPattern = context.getConfig("entryPointPattern", ".*Servlet$|.*Controller$|.*Filter$");
        mdcKey = context.getConfig("mdcKey", "traceId");
        enabled = "true".equalsIgnoreCase(context.getConfig("enabled", "true"));
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled) return;

        // Entry point interceptor — generates traceId
        registry.register(
            interceptClassPattern(entryPointPattern)
                .anyMethod()
                .around(
                    inv -> entryPointInterceptor.before(inv),
                    inv -> entryPointInterceptor.after(inv)
                )
                .priority(1)  // highest priority — trace must be set before other plugins
                .build()
        );
    }

    private final Interceptor entryPointInterceptor = new Interceptor() {
        @Override
        public void before(MethodInvocation inv) {
            // Generate traceId: timestamp-processId-counter
            String traceId = System.currentTimeMillis() + "-" + getProcessId() + "-" + traceCounter.incrementAndGet();
            ThreadContext.put("traceId", traceId);

            // Set MDC for SLF4J integration
            try {
                org.slf4j.MDC.put(mdcKey, traceId);
            } catch (Exception e) {
                // MDC not available — ignore
            }

            if (log.isDebugEnabled()) {
                log.debug("[TRACE] Started trace: {} for {}.{}", traceId, inv.getTargetClass().getSimpleName(), inv.getMethodName());
            }
        }

        @Override
        public void after(MethodInvocation inv) {
            try {
                org.slf4j.MDC.remove(mdcKey);
            } catch (Exception e) {
                // Ignore
            }
            ThreadContext.remove("traceId");
        }

        @Override
        public void onException(MethodInvocation inv) {
            try {
                org.slf4j.MDC.remove(mdcKey);
            } catch (Exception e) {
                // Ignore
            }
            ThreadContext.remove("traceId");
        }
    };

    private String getProcessId() {
        try {
            // Java 9+ has ProcessHandle, but we target Java 8
            // Use a simple fallback
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        } catch (Exception e) {
            return "0";
        }
    }
}
