// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoader.java
package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads interceptor definitions from YAML configuration files.
 */
public class YamlConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigLoader.class);

    public WeaverConfig loadFromFile(String filePath, InterceptorRegistry registry) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.warn("Configuration file not found: {}", filePath);
            return new WeaverConfig();
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            return loadFromReader(reader, registry);
        } catch (IOException e) {
            log.error("Failed to read config file {}: {}", filePath, e.getMessage());
            return new WeaverConfig();
        }
    }

    public WeaverConfig loadFromReader(Reader reader, InterceptorRegistry registry) {
        try {
            Yaml yaml = new Yaml();
            WeaverConfig config = yaml.loadAs(reader, WeaverConfig.class);
            if (config == null) {
                config = new WeaverConfig();
            }
            registerFromConfig(config, registry);
            return config;
        } catch (YAMLException e) {
            log.error("Failed to parse YAML config: {}", e.getMessage());
            return new WeaverConfig();
        }
    }

    private void registerFromConfig(WeaverConfig config, InterceptorRegistry registry) {
        if (config == null || config.getInterceptors() == null) {
            return;
        }

        for (WeaverConfig.InterceptorConfig ic : config.getInterceptors()) {
            try {
                registerInterceptorConfig(ic, registry);
            } catch (Exception e) {
                log.error("Failed to register interceptor for class {}: {}",
                        ic.getClassName(), e.getMessage());
            }
        }
    }

    private void registerInterceptorConfig(WeaverConfig.InterceptorConfig ic, InterceptorRegistry registry) {
        ClassMatcher classMatcher = ic.getClassPattern() != null && !ic.getClassPattern().isEmpty()
                ? ClassMatcher.byNamePattern(ic.getClassPattern())
                : ClassMatcher.byName(ic.getClassName());

        MethodMatcher methodMatcher = ic.getMethodPattern() != null && !ic.getMethodPattern().isEmpty()
                ? MethodMatcher.byNamePattern(ic.getMethodPattern())
                : (ic.getMethod() != null ? MethodMatcher.byName(ic.getMethod()) : MethodMatcher.any());

        Interceptor interceptor = buildInterceptor(ic);
        Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
        String name = "yaml-" + ic.getClassName() + "-" + (ic.getMethod() != null ? ic.getMethod() : "*");

        InterceptorDefinition definition = new InterceptorDefinition(name, pointcut, interceptor, ic.getPriority());
        registry.register(definition);
    }

    private Interceptor buildInterceptor(WeaverConfig.InterceptorConfig ic) {
        return new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invokeAdviceClass(ic.getBefore(), invocation, AdvicePhase.BEFORE);
                invokeAdviceClass(ic.getAround(), invocation, AdvicePhase.BEFORE);
            }

            @Override
            public void after(MethodInvocation invocation) {
                invokeAdviceClass(ic.getAround(), invocation, AdvicePhase.AFTER);
                invokeAdviceClass(ic.getAfter(), invocation, AdvicePhase.AFTER);
            }

            @Override
            public void onException(MethodInvocation invocation) {
                invokeAdviceClass(ic.getAround(), invocation, AdvicePhase.ON_EXCEPTION);
            }
        };
    }

    private enum AdvicePhase {
        BEFORE, AFTER, ON_EXCEPTION
    }

    private void invokeAdviceClass(String adviceClassName, MethodInvocation invocation, AdvicePhase phase) {
        if (adviceClassName == null || adviceClassName.isEmpty()) {
            return;
        }
        try {
            Class<?> adviceClass = Class.forName(adviceClassName);
            Object instance = adviceClass.getDeclaredConstructor().newInstance();
            if (instance instanceof Interceptor) {
                Interceptor advice = (Interceptor) instance;
                switch (phase) {
                    case BEFORE:
                        advice.before(invocation);
                        break;
                    case AFTER:
                        advice.after(invocation);
                        break;
                    case ON_EXCEPTION:
                        advice.onException(invocation);
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to invoke advice class {}: {}", adviceClassName, e.getMessage());
        }
    }
}
