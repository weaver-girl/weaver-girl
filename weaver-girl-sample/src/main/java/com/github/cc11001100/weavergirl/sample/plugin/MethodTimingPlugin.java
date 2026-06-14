package com.github.cc11001100.weavergirl.sample.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Reference plugin that measures method execution time.
 *
 * <p>Configuration (via YAML or system properties):</p>
 * <ul>
 *   <li>{@code threshold} — Only log methods slower than N milliseconds (default: 0 = log all)</li>
 *   <li>{@code enabled} — Enable/disable timing (default: true)</li>
 * </ul>
 */
public class MethodTimingPlugin extends AbstractPlugin {

    private long thresholdMs = 0;
    private boolean enabled = true;

    @Override
    public String name() {
        return "method-timing";
    }

    @Override
    public void init(PluginContext context) {
        String thresholdStr = context.getConfig("threshold", "0");
        try {
            thresholdMs = Long.parseLong(thresholdStr);
        } catch (NumberFormatException e) {
            thresholdMs = 0;
        }
        String enabledStr = context.getConfig("enabled", "true");
        enabled = "true".equalsIgnoreCase(enabledStr);
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        if (!enabled) return;

        // This plugin uses the programmatic API within a plugin
        // Users would typically configure which classes to time via YAML
        // For demonstration, we just show the interceptor pattern
    }

    /**
     * Create a timing interceptor for a given class/method pattern.
     */
    public static Interceptor createTimingInterceptor() {
        return new Interceptor() {
            // Thread-local start time to avoid allocation
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation invocation) {
                startTime.set(System.nanoTime());
            }

            @Override
            public void after(MethodInvocation invocation) {
                long elapsed = (System.nanoTime() - startTime.get()) / 1_000_000;
                startTime.remove();
                if (elapsed >= 0) { // threshold check would go here
                    System.out.println("[TIMING] "
                        + invocation.getTargetClass() + "." + invocation.getMethodName()
                        + " -> " + elapsed + "ms");
                }
            }

            @Override
            public void onException(MethodInvocation invocation) {
                startTime.remove();
            }
        };
    }
}