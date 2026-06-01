// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
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
        log.info("WeaverGirl agent starting...");
        WeaverGirl weaverGirl = new WeaverGirl();
        weaverGirl.instrumentation = instrumentation;

        InterceptorHolder.setRegistry(weaverGirl.registry);

        weaverGirl.pluginLoader.loadPlugins(WeaverGirl.class.getClassLoader(), weaverGirl.registry,
                config != null ? config : Collections.emptyMap());

        WeaverTransformer transformer = new WeaverTransformer(weaverGirl.registry);

        if (weaverConfig != null && weaverConfig.getExcludedClasses() != null) {
            transformer.setExcludedClassPatterns(weaverConfig.getExcludedClasses());
        }

        transformer.install(instrumentation);
        weaverGirl.transformer = transformer;

        JmxRegistrar.register();

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
        if (samplingMonitor != null) {
            samplingMonitor.stop();
        }
        pluginLoader.destroyAll();
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
        return new InterceptBuilder(this, className);
    }

    public InterceptorRegistry getRegistry() {
        return registry;
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
}
