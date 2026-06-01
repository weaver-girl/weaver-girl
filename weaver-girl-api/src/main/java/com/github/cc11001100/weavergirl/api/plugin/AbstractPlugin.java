package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Base plugin class providing a convenient fluent API for registering interceptors.
 *
 * <p>Example usage:</p>
 * <pre>
 * public class MyPlugin extends AbstractPlugin {
 *     public String name() { return "my-plugin"; }
 *     public void registerInterceptors(InterceptorRegistry registry) {
 *         registry.register(
 *             intercept("com.example.Service")
 *                 .method("process")
 *                 .before(inv -&gt; System.out.println("before: " + inv.getMethodName()))
 *                 .build()
 *         );
 *         registry.register(
 *             interceptClassPattern("com\\.example\\..*Service")
 *                 .methodPattern("process.*")
 *                 .around(
 *                     inv -&gt; System.out.println("before: " + inv.getMethodName()),
 *                     inv -&gt; System.out.println("after: " + inv.getMethodName())
 *                 )
 *                 .build()
 *         );
 *     }
 * }
 * </pre>
 */
public abstract class AbstractPlugin implements WeaverPlugin {

    /**
     * Start a fluent interceptor definition for a class matched by exact name.
     */
    protected InterceptorDefinitionBuilder intercept(String className) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byName(className));
    }

    /**
     * Start a fluent interceptor definition for classes matched by regex pattern.
     */
    protected InterceptorDefinitionBuilder interceptClassPattern(String classPattern) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byNamePattern(classPattern));
    }

    /**
     * Start a fluent interceptor definition for classes annotated with the given annotation.
     */
    protected InterceptorDefinitionBuilder interceptAnnotated(String annotationClassName) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byAnnotation(annotationClassName));
    }

    /**
     * Start a fluent interceptor definition for classes that subclass the given class.
     */
    protected InterceptorDefinitionBuilder interceptSubclassOf(String superClassName) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.bySuperClass(superClassName));
    }

    /**
     * Start a fluent interceptor definition for classes that implement the given interface.
     */
    protected InterceptorDefinitionBuilder interceptImplementing(String interfaceName) {
        return new InterceptorDefinitionBuilder(this, ClassMatcher.byInterface(interfaceName));
    }

    /**
     * Helper class for building interceptor definitions fluently.
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
         */
        public InterceptorDefinitionBuilder method(String methodName) {
            this.methodMatcher = MethodMatcher.byName(methodName);
            return this;
        }

        /**
         * Match methods by regex pattern.
         */
        public InterceptorDefinitionBuilder methodPattern(String methodPattern) {
            this.methodMatcher = MethodMatcher.byNamePattern(methodPattern);
            return this;
        }

        /**
         * Match methods annotated with the given annotation.
         */
        public InterceptorDefinitionBuilder methodAnnotated(String annotationClassName) {
            this.methodMatcher = MethodMatcher.byAnnotation(annotationClassName);
            return this;
        }

        /**
         * Match all methods (default).
         */
        public InterceptorDefinitionBuilder anyMethod() {
            this.methodMatcher = MethodMatcher.any();
            return this;
        }

        /**
         * Set the priority for this interceptor definition.
         * Lower values = higher priority (executed first).
         */
        public InterceptorDefinitionBuilder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public InterceptorDefinitionBuilder before(final BeforeCallback callback) {
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    callback.before(invocation);
                }
            };
            return this;
        }

        public InterceptorDefinitionBuilder after(final AfterCallback callback) {
            this.interceptor = new Interceptor() {
                @Override
                public void after(MethodInvocation invocation) {
                    callback.after(invocation);
                }
            };
            return this;
        }

        public InterceptorDefinitionBuilder onException(final ExceptionCallback callback) {
            this.interceptor = new Interceptor() {
                @Override
                public void onException(MethodInvocation invocation) {
                    callback.onException(invocation);
                }
            };
            return this;
        }

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

    @FunctionalInterface
    protected interface BeforeCallback {
        void before(MethodInvocation invocation);
    }

    @FunctionalInterface
    protected interface AfterCallback {
        void after(MethodInvocation invocation);
    }

    @FunctionalInterface
    protected interface ExceptionCallback {
        void onException(MethodInvocation invocation);
    }
}
