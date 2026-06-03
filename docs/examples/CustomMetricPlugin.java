package com.github.cc11001100.weavergirl.docs.examples;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Example: Custom Metrics Plugin with conditional activation.
 *
 * <p>This plugin demonstrates:</p>
 * <ul>
 *   <li>Conditional enable/disable via {@link #isEnabled(PluginContext)}</li>
 *   <li>Event publishing to the InterceptorEventPublisher</li>
 *   <li>Type-safe configuration with defaults</li>
 *   <li>Structured event attributes for observability</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>
 * # In weaver.yml under plugins section:
 * custom-metric:
 *   enabled: true
 *   targetClass: com.example.service.UserService
 *   targetMethod: "*"
 *   slowThresholdMs: 500
 * </pre>
 *
 * <p>Or via system property:</p>
 * <pre>
 * -Dweavergirl.plugin.custom-metric.enabled=true
 * -Dweavergirl.plugin.custom-metric.slowThresholdMs=500
 * </pre>
 *
 * @since 1.0.0
 */
public class CustomMetricPlugin extends AbstractPlugin {

    private long slowThresholdMs = 500;
    private String targetClass = "com.example.Service";
    private String targetMethod = "*";
    private boolean configEnabled = true;

    @Override
    public String name() {
        return "custom-metric";
    }

    @Override
    public void init(PluginContext context) {
        // Use typed config getters with defaults — invalid values get safe defaults
        slowThresholdMs = context.getConfigLong("slowThresholdMs", 500L);
        targetClass = context.getConfig("targetClass", "com.example.Service");
        targetMethod = context.getConfig("targetMethod", "*");
        configEnabled = context.getConfigBoolean("enabled", true);
    }

    /**
     * Conditional activation: only register interceptors if enabled in config.
     */
    @Override
    public boolean isEnabled(PluginContext context) {
        return context.getConfigBoolean("enabled", true);
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        if (!configEnabled) return;

        // Build the pointcut using the API
        Pointcut pointcut = new Pointcut(
                ClassMatcher.byName(targetClass),
                "*".equals(targetMethod) ? MethodMatcher.any() : MethodMatcher.byName(targetMethod)
        );

        // Create the interceptor with metrics collection
        Interceptor interceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation invocation) {
                startTime.set(System.nanoTime());
            }

            @Override
            public void after(MethodInvocation invocation) {
                long elapsedNs = System.nanoTime() - startTime.get();
                startTime.remove();
                long elapsedMs = elapsedNs / 1_000_000;

                // Publish a structured event
                InterceptorEvent event = InterceptorEvent.builder()
                        .type(elapsedMs > slowThresholdMs ? "slow-operation" : "operation")
                        .plugin(name())
                        .className(invocation.getTargetClass().getName())
                        .methodName(invocation.getMethodName())
                        .durationMs(elapsedMs)
                        .attribute("threshold", String.valueOf(slowThresholdMs))
                        .attribute("slow", String.valueOf(elapsedMs > slowThresholdMs))
                        .build();

                InterceptorEventPublisher.getInstance().publish(event);
            }

            @Override
            public void onException(MethodInvocation invocation) {
                startTime.remove();

                // Publish error event
                InterceptorEvent event = InterceptorEvent.builder()
                        .type("operation-error")
                        .plugin(name())
                        .className(invocation.getTargetClass().getName())
                        .methodName(invocation.getMethodName())
                        .attribute("error", "exception during method execution")
                        .build();

                InterceptorEventPublisher.getInstance().publish(event);
            }
        };

        // Register the interceptor definition
        InterceptorDefinition definition = new InterceptorDefinition(
                name() + "-" + targetClass + "-" + targetMethod,
                pointcut,
                interceptor,
                0
        );

        registry.register(definition);
    }

    @Override
    public void destroy() {
        // Flush any pending metrics, close connections, etc.
        // This is called during agent shutdown
    }
}
