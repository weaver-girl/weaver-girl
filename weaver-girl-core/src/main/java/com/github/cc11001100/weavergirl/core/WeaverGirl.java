package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.management.AgentMonitor;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor;
import com.github.cc11001100.weavergirl.core.status.JmxRegistrar;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.github.cc11001100.weavergirl.core.config.ConfigWatcher;

/**
 * Main entry point for the weaver-girl framework.
 * Provides both a fluent programmatic API and internal bootstrap logic.
 */
public class WeaverGirl {

    private static final Logger log = LoggerFactory.getLogger(WeaverGirl.class);

    private final InterceptorRegistry registry;
    private final PluginLoader pluginLoader;
    private Instrumentation instrumentation;
    private WeaverTransformer transformer;
    private SamplingMonitor samplingMonitor;
    private ConfigWatcher configWatcher;

    private WeaverGirl() {
        this.registry = new DefaultInterceptorRegistry();
        this.pluginLoader = new PluginLoader();
    }

    /**
     * Bootstrap the agent — called from premain/agentmain.
     * Loads plugins via SPI and installs the transformer.
     *
     * @param instrumentation the JVM Instrumentation instance
     * @param config agent configuration properties (may be empty)
     */
    public static WeaverGirl bootstrap(Instrumentation instrumentation, java.util.Map<String, String> config) {
        return bootstrap(instrumentation, config, null);
    }

    /**
     * Bootstrap the agent — called from premain/agentmain.
     * Loads plugins via SPI and installs the transformer, applying
     * excludedClasses from WeaverConfig to the transformer's ignore matcher.
     *
     * @param instrumentation the JVM Instrumentation instance
     * @param config agent configuration properties (may be empty)
     * @param weaverConfig YAML configuration (may be null)
     */
    public static WeaverGirl bootstrap(Instrumentation instrumentation, java.util.Map<String, String> config, WeaverConfig weaverConfig) {
        ValidationUtils.requireNonNull(instrumentation, "instrumentation");
        log.info("WeaverGirl agent starting...");
        WeaverGirl weaverGirl = new WeaverGirl();
        weaverGirl.instrumentation = instrumentation;

        InterceptorHolder.setRegistry(weaverGirl.registry);

        // Merge disabledPlugins from WeaverConfig into the config map for PluginLoader
        java.util.Map<String, String> pluginConfig = config != null ? config : new java.util.HashMap<>();
        if (weaverConfig != null && weaverConfig.getDisabledPlugins() != null
                && !weaverConfig.getDisabledPlugins().isEmpty()) {
            String disabledStr = String.join(",", weaverConfig.getDisabledPlugins());
            pluginConfig.put("disabledPlugins", disabledStr);
            log.info("Disabled plugins from config: {}", disabledStr);
        }

        weaverGirl.pluginLoader.loadPlugins(WeaverGirl.class.getClassLoader(), weaverGirl.registry,
                pluginConfig);

        WeaverTransformer transformer = new WeaverTransformer(weaverGirl.registry);

        if (weaverConfig != null && weaverConfig.getExcludedClasses() != null) {
            transformer.setExcludedClassPatterns(weaverConfig.getExcludedClasses());
        }

        if (weaverConfig != null) {
            transformer.setWeaverConfig(weaverConfig);
        }

        transformer.install(instrumentation);
        weaverGirl.transformer = transformer;

        // Apply configuration to core components
        applyCoreConfig(pluginConfig);

        JmxRegistrar.register();

        // Register JMX monitoring MBean
        AgentMonitor monitor = AgentMonitor.getInstance();
        monitor.register();
        monitor.setInterceptorDefinitionCount(weaverGirl.registry.getAllDefinitions().size());
        monitor.setPluginCount(weaverGirl.pluginLoader.getLoadedPlugins().size());

        SamplingMonitor samplingMonitor = new SamplingMonitor(SamplingController.getInstance());
        samplingMonitor.start();
        weaverGirl.samplingMonitor = samplingMonitor;

        log.info("WeaverGirl agent started with {} interceptor definitions",
                weaverGirl.registry.getAllDefinitions().size());
        return weaverGirl;
    }

    /**
     * Bootstrap the agent — called from premain/agentmain.
     * Loads plugins via SPI and installs the transformer.
     */
    public static WeaverGirl bootstrap(Instrumentation instrumentation) {
        return bootstrap(instrumentation, Collections.emptyMap(), null);
    }

    /**
     * Shutdown the agent — destroy all plugins and clean up.
     * Should be called from a shutdown hook.
     */
    public void shutdown() {
        log.info("WeaverGirl agent shutting down...");
        if (configWatcher != null) {
            configWatcher.stop();
        }
        if (samplingMonitor != null) {
            samplingMonitor.stop();
        }
        pluginLoader.destroyAll();
        AgentMonitor.getInstance().unregister();
        JmxRegistrar.unregister();
        InterceptorHolder.setRegistry(null);
        log.info("WeaverGirl agent shut down complete");
    }

    /**
     * Create a new WeaverGirl instance for programmatic API usage.
     */
    public static WeaverGirl create() {
        return new WeaverGirl();
    }

    /**
     * Connect this WeaverGirl instance to the ByteBuddy transformation pipeline.
     * Required for programmatic interceptors to actually take effect.
     *
     * @param instrumentation the JVM Instrumentation instance
     * @return this WeaverGirl instance for chaining
     */
    public WeaverGirl withInstrumentation(Instrumentation instrumentation) {
        ValidationUtils.requireNonNull(instrumentation, "instrumentation");
        this.instrumentation = instrumentation;
        InterceptorHolder.setRegistry(this.registry);
        WeaverTransformer transformer = new WeaverTransformer(this.registry);
        transformer.install(instrumentation);
        this.transformer = transformer;
        return this;
    }

    /**
     * Start a fluent interceptor definition for the given class name.
     */
    public InterceptBuilder intercept(String className) {
        ValidationUtils.requireNonEmpty(className, "className");
        return new InterceptBuilder(this, className);
    }

    public InterceptorRegistry getRegistry() {
        return registry;
    }

    /**
     * Set the ConfigWatcher so it can be stopped during shutdown.
     */
    public void setConfigWatcher(ConfigWatcher watcher) {
        this.configWatcher = watcher;
    }

    /**
     * Returns a list of class names that have been transformed by this agent.
     * Useful for diagnostic purposes.
     */
    public List<String> getTransformedClasses() {
        return AgentStatus.getInstance().getTransformedClasses();
    }

    /**
     * Retransform already-loaded classes that match any registered interceptor.
     * This is needed when the agent is attached dynamically via agentmain,
     * because classes loaded before the agent started would not be transformed.
     *
     * @return the number of classes that were retransformed
     */
    public int retransformLoadedClasses() {
        if (transformer == null) {
            return 0;
        }
        return transformer.retransformLoadedClasses();
    }

    /**
     * Fluent builder for programmatic interceptor registration.
     */
    public static class InterceptBuilder {
        private final WeaverGirl weaverGirl;
        private final String className;
        private String methodName = "*";
        private int priority = 0;
        private Interceptor interceptor;

        InterceptBuilder(WeaverGirl weaverGirl, String className) {
            this.weaverGirl = weaverGirl;
            this.className = className;
        }

        public InterceptBuilder method(String methodName) {
            ValidationUtils.requireNonEmpty(methodName, "methodName");
            this.methodName = methodName;
            return this;
        }

        public InterceptBuilder before(final Consumer<MethodInvocation> callback) {
            Interceptor existing = this.interceptor;
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    callback.accept(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    if (existing != null) existing.after(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    if (existing != null) existing.onException(invocation);
                }
            };
            return this;
        }

        public InterceptBuilder after(final Consumer<MethodInvocation> callback) {
            Interceptor existing = this.interceptor;
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    if (existing != null) existing.before(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    callback.accept(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    if (existing != null) existing.onException(invocation);
                }
            };
            return this;
        }

        public InterceptBuilder onException(final Consumer<MethodInvocation> callback) {
            Interceptor existing = this.interceptor;
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    if (existing != null) existing.before(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    if (existing != null) existing.after(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    callback.accept(invocation);
                }
            };
            return this;
        }

        public WeaverGirl install() {
            ClassMatcher classMatcher = ClassMatcher.byName(className);
            MethodMatcher methodMatcher = "*".equals(methodName)
                    ? MethodMatcher.any() : MethodMatcher.byName(methodName);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            if (interceptor == null) {
                interceptor = new Interceptor() {};
            }
            InterceptorDefinition definition = new InterceptorDefinition(
                    "programmatic-" + className + "-" + methodName, pointcut, interceptor, priority);
            weaverGirl.registry.register(definition);
            return weaverGirl;
        }

        public InterceptBuilder priority(int priority) {
            this.priority = priority;
            return this;
        }
    }

    /**
     * Apply agent configuration to core runtime components.
     * Reads config keys from the agent args map and configures:
     * <ul>
     *   <li>{@code samplingRate} — initial sampling rate (default: 1)</li>
     *   <li>{@code samplingMaxRate} — max sampling rate under load (default: 100)</li>
     *   <li>{@code samplingThreshold} — invocations/sec threshold for adaptation (default: 10000)</li>
     *   <li>{@code circuitBreakerThreshold} — consecutive failures to open breaker (default: 5)</li>
     *   <li>{@code circuitBreakerCooldownMs} — cooldown before retry (default: 60000)</li>
     *   <li>{@code metricsPath} — Prometheus metrics endpoint path (default: /metrics)</li>
     * </ul>
     */
    static void applyCoreConfig(java.util.Map<String, String> config) {
        if (config == null) return;

        // SamplingController configuration
        SamplingController sampling = SamplingController.getInstance();
        String samplingRate = config.get("samplingRate");
        if (samplingRate != null) {
            try {
                sampling.setSamplingRate(Integer.parseInt(samplingRate.trim()));
                log.info("Config: samplingRate={}", samplingRate);
            } catch (NumberFormatException e) {
                log.warn("Invalid samplingRate '{}', using default", samplingRate);
            }
        }
        String samplingMaxRate = config.get("samplingMaxRate");
        if (samplingMaxRate != null) {
            try {
                sampling.setMaxRate(Integer.parseInt(samplingMaxRate.trim()));
                log.info("Config: samplingMaxRate={}", samplingMaxRate);
            } catch (NumberFormatException e) {
                log.warn("Invalid samplingMaxRate '{}', using default", samplingMaxRate);
            }
        }
        String samplingThreshold = config.get("samplingThreshold");
        if (samplingThreshold != null) {
            try {
                sampling.setThresholdInvocationsPerSecond(Long.parseLong(samplingThreshold.trim()));
                log.info("Config: samplingThreshold={}", samplingThreshold);
            } catch (NumberFormatException e) {
                log.warn("Invalid samplingThreshold '{}', using default", samplingThreshold);
            }
        }

        // Circuit breaker configuration (via InterceptorHolder)
        String cbThreshold = config.get("circuitBreakerThreshold");
        if (cbThreshold != null) {
            log.info("Config: circuitBreakerThreshold={} (applied to new instances)", cbThreshold);
        }
        String cbCooldown = config.get("circuitBreakerCooldownMs");
        if (cbCooldown != null) {
            log.info("Config: circuitBreakerCooldownMs={} (applied to new instances)", cbCooldown);
        }
    }
}
