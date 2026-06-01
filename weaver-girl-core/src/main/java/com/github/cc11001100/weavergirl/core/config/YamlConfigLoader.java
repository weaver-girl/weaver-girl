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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads interceptor definitions from YAML configuration files.
 */
public class YamlConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigLoader.class);

    private final ConcurrentHashMap<String, Interceptor> adviceCache = new ConcurrentHashMap<>();

    /**
     * Parse a YAML config file without registering interceptors.
     * Useful when you need the WeaverConfig (e.g., excludedClasses) before
     * the InterceptorRegistry is available.
     *
     * @param filePath path to the YAML config file
     * @return parsed WeaverConfig, or an empty WeaverConfig on failure
     */
    public WeaverConfig parseFromFile(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.warn("Configuration file not found: {}", filePath);
            return new WeaverConfig();
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            Yaml yaml = new Yaml();
            WeaverConfig config = yaml.loadAs(reader, WeaverConfig.class);
            if (config == null) {
                config = new WeaverConfig();
            }
            applyDefaults(config);
            validate(config);
            return config;
        } catch (IOException e) {
            log.error("Failed to read config file {}: {}", filePath, e.getMessage());
            return new WeaverConfig();
        } catch (YAMLException e) {
            log.error("Failed to parse YAML config: {}", e.getMessage());
            return new WeaverConfig();
        }
    }

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

    public WeaverConfig loadFromString(String yaml, InterceptorRegistry registry) {
        return loadFromReader(new StringReader(yaml), registry);
    }

    public WeaverConfig loadFromReader(Reader reader, InterceptorRegistry registry) {
        try {
            Yaml yaml = new Yaml();
            WeaverConfig config = yaml.loadAs(reader, WeaverConfig.class);
            if (config == null) {
                config = new WeaverConfig();
            }
            applyDefaults(config);
            validate(config);
            registerFromConfig(config, registry);
            return config;
        } catch (YAMLException e) {
            log.error("Failed to parse YAML config: {}", e.getMessage());
            return new WeaverConfig();
        }
    }

    /**
     * Apply sensible defaults for agent-level settings that were not specified in the YAML.
     */
    private void applyDefaults(WeaverConfig config) {
        if (config.getSamplingThreshold() == null) {
            config.setSamplingThreshold(100);
        }
        if (config.getCircuitBreakerFailures() == null) {
            config.setCircuitBreakerFailures(5);
        }
        if (config.getCircuitBreakerCooldown() == null) {
            config.setCircuitBreakerCooldown(30000L);
        }
        if (config.getExcludedClasses() == null) {
            config.setExcludedClasses(new ArrayList<>());
        }
        if (config.getLogLevel() == null) {
            config.setLogLevel("INFO");
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
            Interceptor advice = adviceCache.computeIfAbsent(adviceClassName, name -> {
                try {
                    Class<?> adviceClass = Class.forName(name);
                    Object instance = adviceClass.getDeclaredConstructor().newInstance();
                    if (instance instanceof Interceptor) {
                        return (Interceptor) instance;
                    }
                    log.warn("Advice class {} does not implement Interceptor", name);
                    return null;
                } catch (Exception e) {
                    log.warn("Failed to instantiate advice class {}: {}", name, e.getMessage());
                    return null;
                }
            });
            if (advice != null) {
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

    /**
     * Validates the loaded configuration, removing invalid interceptor entries
     * and logging warnings for each skipped entry.
     *
     * <p>Structural validation only: checks that required fields are present.
     * Does NOT validate that advice classes exist on the classpath — that is
     * checked lazily at invocation time, because the class may be loaded by
     * a different ClassLoader or may not be available during agent init.</p>
     */
    private void validate(WeaverConfig config) {
        List<WeaverConfig.InterceptorConfig> valid = new ArrayList<>();
        for (WeaverConfig.InterceptorConfig ic : config.getInterceptors()) {
            List<String> errors = new ArrayList<>();
            if ((ic.getClassName() == null || ic.getClassName().isEmpty())
                    && (ic.getClassPattern() == null || ic.getClassPattern().isEmpty())) {
                errors.add("no className or classPattern specified");
            }
            if ((ic.getBefore() == null || ic.getBefore().isEmpty())
                    && (ic.getAfter() == null || ic.getAfter().isEmpty())
                    && (ic.getAround() == null || ic.getAround().isEmpty())) {
                errors.add("no before, after, or around advice class specified");
            }

            // Validate regex patterns
            if (ic.getClassPattern() != null && !ic.getClassPattern().isEmpty()) {
                try {
                    java.util.regex.Pattern.compile(ic.getClassPattern());
                } catch (java.util.regex.PatternSyntaxException e) {
                    errors.add("invalid classPattern regex: " + e.getMessage());
                }
            }
            if (ic.getMethodPattern() != null && !ic.getMethodPattern().isEmpty()) {
                try {
                    java.util.regex.Pattern.compile(ic.getMethodPattern());
                } catch (java.util.regex.PatternSyntaxException e) {
                    errors.add("invalid methodPattern regex: " + e.getMessage());
                }
            }

            if (errors.isEmpty()) {
                valid.add(ic);
            } else {
                log.warn("Skipping invalid interceptor config for class '{}': {}", ic.getClassName(), String.join(", ", errors));
            }
        }
        config.setInterceptors(valid);
    }
}