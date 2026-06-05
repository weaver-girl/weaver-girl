package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutParser;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Scans classes for weaver-girl annotations and registers them as interceptor definitions.
 * Supports @WeaveClass with @Before, @After, and @Around method annotations.
 */
public class AnnotationPluginLoader {

    private static final Logger log = LoggerFactory.getLogger(AnnotationPluginLoader.class);

    /**
     * Scan a set of annotated classes and register their interceptors.
     */
    public void loadAnnotatedInterceptors(Set<Class<?>> annotatedClasses, InterceptorRegistry registry) {
        for (Class<?> clazz : annotatedClasses) {
            try {
                loadFromAnnotatedClass(clazz, registry);
            } catch (Exception e) {
                log.error("Failed to load annotated interceptor class {}: {}", clazz.getName(), e.getMessage(), e);
            }
        }
    }

    private void loadFromAnnotatedClass(Class<?> clazz, InterceptorRegistry registry) {
        WeaveClass weaveClass = clazz.getAnnotation(WeaveClass.class);
        if (weaveClass == null) {
            log.warn("Class {} has no @WeaveClass annotation, skipping", clazz.getName());
            return;
        }

        Object interceptorInstance;
        try {
            interceptorInstance = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("Cannot instantiate interceptor class {}: {}", clazz.getName(), e.getMessage());
            return;
        }

        Map<String, List<Method>> beforeMethods = new HashMap<>();
        Map<String, List<Method>> afterMethods = new HashMap<>();
        Map<String, List<Method>> aroundMethods = new HashMap<>();

        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Before.class)) {
                String targetMethod = m.getAnnotation(Before.class).value();
                beforeMethods.computeIfAbsent(targetMethod, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(After.class)) {
                String targetMethod = m.getAnnotation(After.class).value();
                afterMethods.computeIfAbsent(targetMethod, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(Around.class)) {
                String targetMethod = m.getAnnotation(Around.class).value();
                aroundMethods.computeIfAbsent(targetMethod, k -> new ArrayList<>()).add(m);
            }
        }

        Set<String> allTargetMethods = new HashSet<>();
        allTargetMethods.addAll(beforeMethods.keySet());
        allTargetMethods.addAll(afterMethods.keySet());
        allTargetMethods.addAll(aroundMethods.keySet());

        // PointcutExpression mode: register a single interceptor with the expression's pointcut
        boolean usePointcutExpression = !weaveClass.pointcut().isEmpty();
        if (usePointcutExpression) {
            PointcutExpression expression = PointcutParser.getInstance().parse(weaveClass.pointcut());
            Pointcut pointcut = expression.toPointcut();
            Interceptor interceptor = createReflectiveInterceptor(interceptorInstance,
                    flattenAll(beforeMethods), flattenAll(afterMethods), flattenAll(aroundMethods));
            String defName = "annotation-" + clazz.getSimpleName();
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor);
            registry.register(definition);
            return;
        }

        // --- Build ClassMatcher from WeaveClass attributes (priority order) ---
        ClassMatcher classMatcher = buildClassMatcherFromAnnotation(weaveClass);

        // Standard mode: one interceptor per target method
        if (allTargetMethods.isEmpty()) {
            // No @Before/@After/@Around methods — register a no-op interceptor matching all methods
            MethodMatcher methodMatcher = MethodMatcher.any();
            Interceptor interceptor = createReflectiveInterceptor(interceptorInstance,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            String defName = "annotation-" + clazz.getSimpleName() + "-all";
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor);
            registry.register(definition);
            return;
        }

        for (String targetMethod : allTargetMethods) {
            List<Method> befores = beforeMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> afters = afterMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> arounds = aroundMethods.getOrDefault(targetMethod, Collections.emptyList());

            MethodMatcher methodMatcher = MethodMatcher.byName(targetMethod);
            Interceptor interceptor = createReflectiveInterceptor(interceptorInstance, befores, afters, arounds);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);

            String defName = "annotation-" + clazz.getSimpleName() + "-" + targetMethod;
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor);
            registry.register(definition);
        }
    }

    /**
     * Build a ClassMatcher from @WeaveClass annotation attributes.
     * Priority order: targetAnnotation > targetSuperClass > targetInterface > targetPattern > target
     */
    private ClassMatcher buildClassMatcherFromAnnotation(WeaveClass weaveClass) {
        if (!weaveClass.targetAnnotation().isEmpty()) {
            return ClassMatcher.byAnnotation(weaveClass.targetAnnotation());
        }
        if (!weaveClass.targetSuperClass().isEmpty()) {
            return ClassMatcher.bySuperClass(weaveClass.targetSuperClass());
        }
        if (!weaveClass.targetInterface().isEmpty()) {
            return ClassMatcher.byInterface(weaveClass.targetInterface());
        }
        if (!weaveClass.targetPattern().isEmpty()) {
            return ClassMatcher.byNamePattern(weaveClass.targetPattern());
        }
        return ClassMatcher.byName(weaveClass.target());
    }

    private List<Method> flattenAll(Map<String, List<Method>> methodsMap) {
        List<Method> result = new ArrayList<>();
        for (List<Method> methods : methodsMap.values()) {
            result.addAll(methods);
        }
        return result;
    }

    private Interceptor createReflectiveInterceptor(Object instance,
                                                    List<Method> befores, List<Method> afters, List<Method> arounds) {
        return new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invokeMethods(instance, arounds, invocation);
                invokeMethods(instance, befores, invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
                invokeMethods(instance, arounds, invocation);
                invokeMethods(instance, afters, invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
                invokeMethods(instance, arounds, invocation);
            }

            private void invokeMethods(Object inst, List<Method> methods, MethodInvocation inv) {
                for (Method m : methods) {
                    try {
                        m.setAccessible(true);
                        m.invoke(inst, inv);
                    } catch (Exception e) {
                        log.warn("Error invoking interceptor method {}: {}", m.getName(), e.getMessage());
                    }
                }
            }
        };
    }
}
