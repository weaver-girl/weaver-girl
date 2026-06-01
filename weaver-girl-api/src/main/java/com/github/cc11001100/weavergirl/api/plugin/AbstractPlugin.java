package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Base plugin class providing a convenient fluent builder API for registering interceptors.
 *
 * <p>Extend this class instead of implementing {@link WeaverPlugin} directly to use
 * the fluent builder pattern, which reduces boilerplate and makes interceptor definitions
 * more readable. Each builder starts with an {@code intercept*()} method that selects the
 * target class, then chains method selection, callback registration, and finally
 * {@link InterceptorDefinitionBuilder#build()} to produce an
 * {@link InterceptorDefinition}.</p>
 *
 * <h3>Fluent builder entry points</h3>
 * <ul>
 *   <li>{@link #intercept(String)} &mdash; match class by exact name</li>
 *   <li>{@link #interceptClassPattern(String)} &mdash; match class by regex pattern</li>
 *   <li>{@link #interceptAnnotated(String)} &mdash; match class by annotation</li>
 *   <li>{@link #interceptSubclassOf(String)} &mdash; match class by superclass</li>
 *   <li>{@link #interceptImplementing(String)} &mdash; match class by interface</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * <pre>
 * public class MyPlugin extends AbstractPlugin {
 *     &#64;Override
 *     public String name() { return "my-plugin"; }
 *
 *     &#64;Override
 *     public void registerInterceptors(InterceptorRegistry registry) {
 *         // Intercept a specific method on a specific class
 *         registry.register(
 *             intercept("com.example.Service")
 *                 .method("process")
 *                 .before(inv -&gt; System.out.println("before: " + inv.getMethodName()))
 *                 .build()
 *         );
 *
 *         // Intercept all methods on classes matching a pattern
 *         registry.register(
 *             interceptClassPattern("com\\.example\\..*Service")
 *                 .methodPattern("process.*")
 *                 .around(
 *                     inv -&gt; System.out.println("before: " + inv.getMethodName()),
 *                     inv -&gt; System.out.println("after: " + inv.getMethodName())
 *                 )
 *                 .build()
 *         );
 *
 *         // Intercept methods on annotated classes with priority
 *         registry.register(
 *             interceptAnnotated("com.example.Traced")
 *                 .anyMethod()
 *                 .after(inv -&gt; recordMetric(inv))
 *                 .priority(10)
 *                 .build()
 *         );
 *     }
 * }</pre>
 *
 * @see WeaverPlugin
 * @see InterceptorDefinition
 * @see InterceptorDefinitionBuilder
 * @since 1.0.0
 */
public abstract class AbstractPlugin implements WeaverPlugin {

    /**
     * Start a fluent interceptor definition for a class matched by exact fully-qualified name.
     *
     * @param className the fully-qualified class name (e.g., {@code "com.example.Service"})
     * @return a new builder with the class matcher set to exact name matching
     */
    protected InterceptorDefinitionBuilder intercept(String className) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byName(className));
    }

    /**
     * Start a fluent interceptor definition for classes matched by regex name pattern.
     *
     * @param classPattern a Java regex pattern (e.g., {@code "com\\.example\\..*Service"})
     * @return a new builder with the class matcher set to name pattern matching
     */
    protected InterceptorDefinitionBuilder interceptClassPattern(String classPattern) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byNamePattern(classPattern));
    }

    /**
     * Start a fluent interceptor definition for classes annotated with the given annotation.
     *
     * @param annotationClassName the fully-qualified annotation class name
     *                            (e.g., {@code "com.example.Traced"})
     * @return a new builder with the class matcher set to annotation matching
     */
    protected InterceptorDefinitionBuilder interceptAnnotated(String annotationClassName) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byAnnotation(annotationClassName));
    }

    /**
     * Start a fluent interceptor definition for classes that subclass the given class.
     *
     * @param superClassName the fully-qualified superclass name
     *                       (e.g., {@code "com.example.BaseService"})
     * @return a new builder with the class matcher set to superclass matching
     */
    protected InterceptorDefinitionBuilder interceptSubclassOf(String superClassName) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.bySuperClass(superClassName));
    }

    /**
     * Start a fluent interceptor definition for classes that implement the given interface.
     *
     * @param interfaceName the fully-qualified interface name
     *                      (e.g., {@code "java.io.Serializable"})
     * @return a new builder with the class matcher set to interface matching
     */
    protected InterceptorDefinitionBuilder interceptImplementing(String interfaceName) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byInterface(interfaceName));
    }

    /**
     * Fluent builder for constructing {@link InterceptorDefinition} instances.
     *
     * <p>Typical usage: start with an {@code intercept*()} method on
     * {@link AbstractPlugin}, chain method selection (e.g., {@link #method(String)}),
     * add callback(s) (e.g., {@link #before(BeforeCallback)}), and call
     * {@link #build()} to produce the definition.</p>
     *
     * <p>If no method matcher is specified, {@link MethodMatcher#any()} is used.
     * If no interceptor callback is set, a no-op interceptor is used.</p>
     *
     * @since 1.0.0
     */
    protected static class InterceptorDefinitionBuilder {
        private final AbstractPlugin plugin;
        private final ClassMatcher classMatcher;
        private MethodMatcher methodMatcher;
        private Interceptor interceptor;
        private int priority = 0;

        InterceptorDefinitionBuilder(AbstractPlugin plugin, ClassMatcher classMatcher) {
            this.plugin = plugin;
            this.classMatcher = classMatcher;
            this.methodMatcher = MethodMatcher.any();
        }

        /**
         * Match methods by exact name.
         *
         * @param methodName the method name to match
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder method(String methodName) {
            this.methodMatcher = MethodMatcher.byName(methodName);
            return this;
        }

        /**
         * Match methods by regex name pattern.
         *
         * @param methodPattern a Java regex pattern against method names
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder methodPattern(String methodPattern) {
            this.methodMatcher = MethodMatcher.byNamePattern(methodPattern);
            return this;
        }

        /**
         * Match methods annotated with the given annotation.
         *
         * @param annotationClassName the fully-qualified annotation class name
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder methodAnnotated(String annotationClassName) {
            this.methodMatcher = MethodMatcher.byAnnotation(annotationClassName);
            return this;
        }

        /**
         * Match all methods (default if no method matcher is specified).
         *
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder anyMethod() {
            this.methodMatcher = MethodMatcher.any();
            return this;
        }

        /**
         * Set the priority for this interceptor definition.
         *
         * <p>Lower values = higher priority (executed first). The default is 0.</p>
         *
         * @param priority the priority value
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Register a callback to be invoked before the target method executes.
         *
         * @param callback the before callback
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder before(final BeforeCallback callback) {
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    callback.before(invocation);
                }
            };
            return this;
        }

        /**
         * Register a callback to be invoked after the target method executes successfully.
         *
         * @param callback the after callback
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder after(final AfterCallback callback) {
            this.interceptor = new Interceptor() {
                @Override
                public void after(MethodInvocation invocation) {
                    callback.after(invocation);
                }
            };
            return this;
        }

        /**
         * Register a callback to be invoked when the target method throws an exception.
         *
         * @param callback the exception callback
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder onException(final ExceptionCallback callback) {
            this.interceptor = new Interceptor() {
                @Override
                public void onException(MethodInvocation invocation) {
                    callback.onException(invocation);
                }
            };
            return this;
        }

        /**
         * Register both a before and after callback, creating an around-advice interceptor.
         *
         * <p>The after callback is also invoked on exception, allowing cleanup logic
         * to run regardless of whether the method succeeded or threw.</p>
         *
         * @param beforeCallback the before callback
         * @param afterCallback  the after callback (also called on exception)
         * @return this builder for chaining
         */
        public InterceptorDefinitionBuilder around(final BeforeCallback beforeCallback, final AfterCallback afterCallback) {
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    beforeCallback.before(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    afterCallback.after(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    afterCallback.after(invocation);
                }
            };
            return this;
        }

        /**
         * Build the interceptor definition from this builder's configuration.
         *
         * <p>The definition name is auto-generated from the plugin name, class matcher
         * pattern, and method matcher pattern. If no interceptor was set, a no-op
         * interceptor is used.</p>
         *
         * @return a new InterceptorDefinition
         */
        public InterceptorDefinition build() {
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            if (interceptor == null) {
                interceptor = new Interceptor() {};
            }
            return new InterceptorDefinition(
                    plugin.name() + "-" + classMatcher.getPattern() + "-" + methodMatcher.getPattern(),
                    pointcut, interceptor, priority);
        }
    }

    /**
     * Functional interface for before-advice callbacks.
     *
     * @since 1.0.0
     */
    @FunctionalInterface
    protected interface BeforeCallback {
        /**
         * Called before the target method executes.
         *
         * @param invocation context object for the intercepted method call
         */
        void before(MethodInvocation invocation);
    }

    /**
     * Functional interface for after-advice callbacks.
     *
     * @since 1.0.0
     */
    @FunctionalInterface
    protected interface AfterCallback {
        /**
         * Called after the target method executes.
         *
         * @param invocation context object for the intercepted method call
         */
        void after(MethodInvocation invocation);
    }

    /**
     * Functional interface for exception-advice callbacks.
     *
     * @since 1.0.0
     */
    @FunctionalInterface
    protected interface ExceptionCallback {
        /**
         * Called when the target method throws an exception.
         *
         * @param invocation context object for the intercepted method call
         */
        void onException(MethodInvocation invocation);
    }
}
