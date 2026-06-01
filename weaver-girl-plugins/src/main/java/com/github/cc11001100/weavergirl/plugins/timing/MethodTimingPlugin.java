package com.github.cc11001100.weavergirl.plugins.timing;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Method execution timing plugin.
 * Measures execution time of methods matching configurable class/method patterns.
 * Detects slow methods exceeding a configurable threshold.
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowThreshold} — Slow method threshold in ms (default: 1000)</li>
 *   <li>{@code classPattern} — Regex pattern for classes to intercept (default: none, must be set)</li>
 *   <li>{@code methodPattern} — Regex pattern for methods to intercept (default: ".*")</li>
 *   <li>{@code logLevel} — Log level for timing output: DEBUG, INFO, WARN (default: INFO)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 */
public class MethodTimingPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(MethodTimingPlugin.class);

    private long slowThresholdMs = 1000;
    private String classPattern;
    private String methodPattern = ".*";
    private String logLevelStr = "INFO";
    private boolean enabled = true;

    @Override
    public String name() {
        return "method-timing";
    }

    @Override
    public void init(PluginContext context) {
        String thresholdStr = context.getConfig("slowThreshold", "1000");
        try { slowThresholdMs = Long.parseLong(thresholdStr); } catch (NumberFormatException e) { slowThresholdMs = 1000; }
        classPattern = context.getConfig("classPattern", null);
        methodPattern = context.getConfig("methodPattern", ".*");
        logLevelStr = context.getConfig("logLevel", "INFO");
        enabled = "true".equalsIgnoreCase(context.getConfig("enabled", "true"));
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled || classPattern == null || classPattern.isEmpty()) return;

        registry.register(
            interceptClassPattern(classPattern)
                .methodPattern(methodPattern)
                .around(
                    inv -> timingInterceptor.before(inv),
                    inv -> timingInterceptor.after(inv)
                )
                .priority(5)
                .build()
        );
    }

    private final Interceptor timingInterceptor = new Interceptor() {
        private final ThreadLocal<Long> startTime = new ThreadLocal<>();

        @Override
        public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());
        }

        @Override
        public void after(MethodInvocation inv) {
            Long start = startTime.get();
            if (start == null) return;
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            startTime.remove();

            String msg = "[TIMING] {}.{} took {}ms";
            if (elapsedMs >= slowThresholdMs) {
                log.warn(msg + " (SLOW, threshold: {}ms)", inv.getTargetClass().getName(), inv.getMethodName(), elapsedMs, slowThresholdMs);
                InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("slow-method")
                                .plugin("method-timing")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .durationMs(elapsedMs)
                                .build()
                );
            } else {
                switch (logLevelStr) {
                    case "DEBUG": log.debug(msg, inv.getTargetClass().getName(), inv.getMethodName(), elapsedMs); break;
                    case "WARN":  log.warn(msg, inv.getTargetClass().getName(), inv.getMethodName(), elapsedMs); break;
                    default:      log.info(msg, inv.getTargetClass().getName(), inv.getMethodName(), elapsedMs); break;
                }
            }
        }

        @Override
        public void onException(MethodInvocation inv) {
            startTime.remove();
        }
    };
}
