package com.github.cc11001100.weavergirl.plugins.spring;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Framework instrumentation plugin.
 * Intercepts classes annotated with @Controller, @Service, @Repository, @Component
 * to measure execution time of public methods.
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowThreshold} — Slow method threshold in ms (default: 3000)</li>
 *   <li>{@code logArguments} — Log method arguments (default: false)</li>
 *   <li>{@code interceptAnnotations} — Comma-separated list of annotations to intercept (default: org.springframework.stereotype.Controller,org.springframework.stereotype.Service,org.springframework.stereotype.Repository,org.springframework.stereotype.Component,org.springframework.web.bind.annotation.RestController)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 *
 * <p>Uses ClassMatcher.byAnnotation() so no compile dependency on Spring.</p>
 */
public class SpringPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(SpringPlugin.class);

    private long slowThresholdMs = 3000;
    private boolean logArguments = false;
    private String[] interceptAnnotations = {
        "org.springframework.stereotype.Controller",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Component",
        "org.springframework.web.bind.annotation.RestController"
    };
    private boolean enabled = true;

    @Override
    public String name() {
        return "spring";
    }

    @Override
    public void init(PluginContext context) {
        String thresholdStr = context.getConfig("slowThreshold", "3000");
        try { slowThresholdMs = Long.parseLong(thresholdStr); } catch (NumberFormatException e) { slowThresholdMs = 3000; }
        logArguments = "true".equalsIgnoreCase(context.getConfig("logArguments", "false"));
        String annotationsStr = context.getConfig("interceptAnnotations", null);
        if (annotationsStr != null && !annotationsStr.isEmpty()) {
            interceptAnnotations = annotationsStr.split(",");
            // Trim whitespace
            for (int i = 0; i < interceptAnnotations.length; i++) {
                interceptAnnotations[i] = interceptAnnotations[i].trim();
            }
        }
        enabled = "true".equalsIgnoreCase(context.getConfig("enabled", "true"));
    }

    @Override
    public void registerInterceptors(com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor springInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());
                if (logArguments && log.isDebugEnabled()) {
                    StringBuilder args = new StringBuilder();
                    Object[] methodArgs = inv.getArguments();
                    if (methodArgs != null) {
                        for (int i = 0; i < methodArgs.length; i++) {
                            if (i > 0) args.append(", ");
                            args.append(methodArgs[i] != null ? methodArgs[i].toString() : "null");
                        }
                    }
                    log.debug("[SPRING] {}.{}({})", inv.getTargetClass().getSimpleName(), inv.getMethodName(), args);
                }
            }

            @Override
            public void after(MethodInvocation inv) {
                Long start = startTime.get();
                startTime.remove();
                if (start != null) {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    if (elapsedMs >= slowThresholdMs) {
                        log.warn("[SLOW-SPRING] {}.{} took {}ms (threshold: {}ms)", inv.getTargetClass().getSimpleName(), inv.getMethodName(), elapsedMs, slowThresholdMs);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[SPRING] {}.{} took {}ms", inv.getTargetClass().getSimpleName(), inv.getMethodName(), elapsedMs);
                    }
                }
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();
                log.warn("[SPRING-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(), inv.getThrowable().getMessage());
            }
        };

        // Register interceptor for each annotation
        for (String annotation : interceptAnnotations) {
            registry.register(interceptAnnotated(annotation)
                .anyMethod()
                .around(
                    inv -> springInterceptor.before(inv),
                    inv -> springInterceptor.after(inv)
                )
                .priority(10)
                .build());
        }
    }
}
