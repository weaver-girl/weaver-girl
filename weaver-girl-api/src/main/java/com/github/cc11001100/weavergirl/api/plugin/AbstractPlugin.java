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
 *     }
 * }
 * </pre>
 */
public abstract class AbstractPlugin implements WeaverPlugin {

    protected InterceptorDefinitionBuilder intercept(String className) {
        return new InterceptorDefinitionBuilder(this, className);
    }

    /**
     * Helper class for building interceptor definitions fluently.
     */
    protected static class InterceptorDefinitionBuilder {
        private final AbstractPlugin plugin;
        private final String className;
        private String methodName = "*";
        private Interceptor interceptor;

        InterceptorDefinitionBuilder(AbstractPlugin plugin, String className) {
            this.plugin = plugin;
            this.className = className;
        }

        public InterceptorDefinitionBuilder method(String methodName) {
            this.methodName = methodName;
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
            ClassMatcher classMatcher = ClassMatcher.byName(className);
            MethodMatcher methodMatcher = "*".equals(methodName)
                    ? MethodMatcher.any() : MethodMatcher.byName(methodName);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            if (interceptor == null) {
                interceptor = new Interceptor() {};
            }
            return new InterceptorDefinition(plugin.name() + "-" + className + "-" + methodName,
                    pointcut, interceptor);
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
}
