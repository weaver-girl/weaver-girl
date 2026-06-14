package com.github.cc11001100.weavergirl.docs.examples;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Example: Minimal audit logging plugin using the WeaverPlugin interface directly.
 *
 * <p>This is the simplest possible plugin — no AbstractPlugin, no events,
 * just straightforward before/after logging. Shows the minimal interface.</p>
 *
 * <h3>Step 1: Implement WeaverPlugin</h3>
 * <pre>
 * public class AuditLogPlugin implements WeaverPlugin {
 *     public String name() { return "audit-log"; }
 *     public void registerInterceptors(InterceptorRegistry registry) { ... }
 * }
 * </pre>
 *
 * <h3>Step 2: Register in SPI</h3>
 * <pre>
 * // File: META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
 * com.github.cc11001100.weavergirl.docs.examples.AuditLogPlugin
 * </pre>
 *
 * <h3>Step 3: Package and deploy</h3>
 * <pre>
 * jar cf audit-plugin.jar META-INF/services/ com/
 * # Copy to plugin directory and add: -javaagent:agent.jar=plugins=/path/to/dir
 * </pre>
 */
public class AuditLogPlugin implements WeaverPlugin {

    @Override
    public String name() {
        return "audit-log";
    }

    @Override
    public void init(PluginContext context) {
        // Read configuration, establish connections, etc.
        String logLevel = context.getConfig("logLevel", "INFO");
        System.out.println("[audit-log] Initialized with logLevel=" + logLevel);
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        // Create a pointcut matching the target class and method
        Pointcut pointcut = new Pointcut(
                ClassMatcher.byName("com.example.service.UserService"),
                MethodMatcher.byName("createUser")
        );

        // Create a simple interceptor
        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                System.out.println("[AUDIT] BEFORE: " +
                        invocation.getTargetClass().getSimpleName() + "." +
                        invocation.getMethodName());
            }

            @Override
            public void after(MethodInvocation invocation) {
                System.out.println("[AUDIT] AFTER: " +
                        invocation.getTargetClass().getSimpleName() + "." +
                        invocation.getMethodName());
            }

            @Override
            public void onException(MethodInvocation invocation) {
                System.out.println("[AUDIT] ERROR: " +
                        invocation.getTargetClass().getSimpleName() + "." +
                        invocation.getMethodName());
            }
        };

        // Register with the framework
        registry.register(new InterceptorDefinition(
                "audit-user-create", pointcut, interceptor, 0));

        System.out.println("[audit-log] Registered audit interceptor");
    }

    @Override
    public String[] depends() {
        // This plugin has no dependencies on other plugins
        return new String[0];
    }

    @Override
    public void destroy() {
        System.out.println("[audit-log] Plugin destroyed");
    }
}
