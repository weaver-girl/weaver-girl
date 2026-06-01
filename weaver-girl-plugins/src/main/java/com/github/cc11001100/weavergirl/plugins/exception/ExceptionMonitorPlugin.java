package com.github.cc11001100.weavergirl.plugins.exception;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exception monitoring plugin.
 * Tracks exceptions thrown by intercepted methods, reports frequency,
 * and alerts on new exception types per method.
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code classPattern} — Regex pattern for classes to monitor (default: none, must be set)</li>
 *   <li>{@code alertOnNewException} — Log WARN for first occurrence of each exception type (default: true)</li>
 *   <li>{@code maxStackTraceDepth} — Max stack trace elements to log (default: 5)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 */
public class ExceptionMonitorPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(ExceptionMonitorPlugin.class);

    private String classPattern;
    private boolean alertOnNewException = true;
    private int maxStackTraceDepth = 5;
    private boolean enabled = true;

    // Track seen exception types per method to detect new ones
    // Key: "ClassName.methodName:ExceptionType"
    private final Set<String> seenExceptions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public String name() {
        return "exception-monitor";
    }

    @Override
    public void init(PluginContext context) {
        classPattern = context.getConfig("classPattern", null);
        alertOnNewException = context.getConfigBoolean("alertOnNewException", true);
        maxStackTraceDepth = context.getConfigInt("maxStackTraceDepth", 5);
        enabled = context.getConfigBoolean("enabled", true);
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled || classPattern == null || classPattern.isEmpty()) return;

        registry.register(
            interceptClassPattern(classPattern)
                .anyMethod()
                .onException(inv -> {
                    Throwable t = inv.getThrowable();
                    if (t == null) return;

                    String location = inv.getTargetClass().getName() + "." + inv.getMethodName();
                    String exceptionKey = location + ":" + t.getClass().getName();
                    boolean isNewException = seenExceptions.add(exceptionKey);

                    if (isNewException && alertOnNewException) {
                        log.warn("[NEW-EXCEPTION] First occurrence of {} at {}", t.getClass().getName(), location);
                        // Log abbreviated stack trace
                        StackTraceElement[] stack = t.getStackTrace();
                        int depth = Math.min(stack.length, maxStackTraceDepth);
                        for (int i = 0; i < depth; i++) {
                            log.warn("  at {}", stack[i]);
                        }
                        if (stack.length > maxStackTraceDepth) {
                            log.warn("  ... {} more", stack.length - maxStackTraceDepth);
                        }
                    } else {
                        log.info("[EXCEPTION] {} at {}: {}", t.getClass().getSimpleName(), location, t.getMessage());
                    }
                    InterceptorEventPublisher.getInstance().publish(
                            InterceptorEvent.builder()
                                    .type("exception")
                                    .plugin("exception-monitor")
                                    .className(inv.getTargetClass().getSimpleName())
                                    .methodName(inv.getMethodName())
                                    .attribute("exceptionType", t.getClass().getName())
                                    .attribute("error", t.getMessage())
                                    .build()
                    );
                })
                .priority(5)
                .build()
        );
    }
}
