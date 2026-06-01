package com.github.cc11001100.weavergirl.plugins.logging;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Logging support plugin.
 * Provides MDC context injection for SLF4J-compatible logging frameworks.
 * Other plugins can declare a dependency on this plugin.
 *
 * <p>This plugin does not intercept any methods. Instead, it provides
 * a static utility API that other plugins call to inject trace context
 * into their MDC.</p>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code mdcKeys} — Comma-separated MDC keys to set (default: traceId,spanId,method)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 */
public class LoggingPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(LoggingPlugin.class);

    private String[] mdcKeys = {"traceId", "spanId", "method"};
    private boolean enabled = true;

    @Override
    public String name() {
        return "logging";
    }

    @Override
    public void init(PluginContext context) {
        String mdcKeysStr = context.getConfig("mdcKeys", "traceId,spanId,method");
        mdcKeys = mdcKeysStr.split(",");
        for (int i = 0; i < mdcKeys.length; i++) {
            mdcKeys[i] = mdcKeys[i].trim();
        }
        enabled = "true".equalsIgnoreCase(context.getConfig("enabled", "true"));
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        // No interceptors — this is a support plugin
        // Other plugins use the static utility methods
    }

    @Override
    public String[] depends() {
        return new String[]{"trace-correlation"};
    }

    // ---- Static utility methods for other plugins ----

    /**
     * Inject trace context into MDC.
     * Call this at the start of an interceptor's before() method.
     */
    public static void injectContext(String className, String methodName) {
        String traceId = (String) ThreadContext.get("traceId");
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        String spanId = (String) ThreadContext.get("spanId");
        if (spanId != null) {
            MDC.put("spanId", spanId);
        }
        MDC.put("method", className + "." + methodName);
    }

    /**
     * Clear MDC context.
     * Call this at the end of an interceptor's after/onException method.
     */
    public static void clearContext() {
        MDC.remove("traceId");
        MDC.remove("spanId");
        MDC.remove("method");
    }

    // Expose for testing
    String[] getMdcKeys() {
        return mdcKeys;
    }

    boolean isEnabled() {
        return enabled;
    }
}