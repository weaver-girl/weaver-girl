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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scans classes for weaver-girl annotations and registers them as interceptor definitions.
 * Supports @WeaveClass with @Before, @After, @Around, @OnException, @AfterReturning,
 * @OnMethodPattern, @WhenAnnotated, @OnConstructor, @Order, @EnableIf, @SampleRate,
 * @Timed, and @RetryOnException annotations.
 *
 * @since 1.0.0
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

        // Check @EnableIf condition
        if (!checkEnableIfCondition(clazz)) {
            log.info("Interceptor {} disabled by @EnableIf condition", clazz.getSimpleName());
            return;
        }

        // Check @SampleRate
        double sampleRate = getSampleRate(clazz);
        if (sampleRate <= 0.0) {
            log.info("Interceptor {} disabled by @SampleRate(0.0)", clazz.getSimpleName());
            return;
        }

        // Read @Order priority if present
        int priority = 0;
        Order orderAnnotation = clazz.getAnnotation(Order.class);
        if (orderAnnotation != null) {
            priority = orderAnnotation.value();
        }

        // Check @Timed
        Timed timedAnnotation = clazz.getAnnotation(Timed.class);
        boolean isTimed = timedAnnotation != null;

        Object interceptorInstance;
        try {
            interceptorInstance = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("Cannot instantiate interceptor class {}: {}", clazz.getName(), e.getMessage());
            return;
        }

        // --- Collect advice methods by category ---
        Map<String, List<Method>> beforeMethods = new HashMap<>();
        Map<String, List<Method>> afterMethods = new HashMap<>();
        Map<String, List<Method>> aroundMethods = new HashMap<>();
        Map<String, List<Method>> onExceptionMethods = new HashMap<>();
        Map<String, List<Method>> afterReturningMethods = new HashMap<>();
        Map<String, List<Method>> retryMethods = new HashMap<>();

        // Methods keyed by regex pattern
        Map<String, List<Method>> methodPatternMethods = new HashMap<>();

        // Methods keyed by annotation class name
        Map<String, List<Method>> whenAnnotatedMethods = new HashMap<>();

        // Constructor methods
        List<Method> constructorMethods = new ArrayList<>();

        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Before.class)) {
                Before ann = m.getAnnotation(Before.class);
                String key = methodKey(ann.value(), ann.parameterTypes());
                beforeMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(After.class)) {
                After ann = m.getAnnotation(After.class);
                String key = methodKey(ann.value(), ann.parameterTypes());
                afterMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(Around.class)) {
                Around ann = m.getAnnotation(Around.class);
                String key = methodKey(ann.value(), ann.parameterTypes());
                aroundMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(OnException.class)) {
                OnException ann = m.getAnnotation(OnException.class);
                String key = methodKey(ann.value(), ann.parameterTypes());
                onExceptionMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(AfterReturning.class)) {
                AfterReturning ann = m.getAnnotation(AfterReturning.class);
                String key = methodKey(ann.value(), ann.parameterTypes());
                afterReturningMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(RetryOnException.class)) {
                RetryOnException ann = m.getAnnotation(RetryOnException.class);
                String key = methodKey(ann.value(), new String[0]);
                retryMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(OnMethodPattern.class)) {
                OnMethodPattern ann = m.getAnnotation(OnMethodPattern.class);
                methodPatternMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(WhenAnnotated.class)) {
                WhenAnnotated ann = m.getAnnotation(WhenAnnotated.class);
                whenAnnotatedMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(OnConstructor.class)) {
                constructorMethods.add(m);
            }
        }

        // --- PointcutExpression mode: register a single interceptor with the expression's pointcut ---
        // This takes precedence — no ClassMatcher or per-method handling needed
        boolean usePointcutExpression = !weaveClass.pointcut().isEmpty();
        if (usePointcutExpression) {
            PointcutExpression expression = PointcutParser.getInstance().parse(weaveClass.pointcut());
            Pointcut pointcut = expression.toPointcut();
            Interceptor interceptor = createReflectiveInterceptor(interceptorInstance,
                    flattenAll(beforeMethods), flattenAll(afterMethods), flattenAll(aroundMethods),
                    flattenAll(onExceptionMethods), flattenAll(afterReturningMethods),
                    sampleRate, isTimed, timedAnnotation);
            String defName = "annotation-" + clazz.getSimpleName();
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor, priority);
            registry.register(definition);
            return;
        }

        // --- Build ClassMatcher (only for non-pointcut-expression modes) ---
        ClassMatcher classMatcher = buildClassMatcherFromAnnotation(weaveClass);

        // --- Handle @OnConstructor separately ---
        if (!constructorMethods.isEmpty()) {
            MethodMatcher constructorMatcher = MethodMatcher.byName("<init>");
            Interceptor constructorInterceptor = createConstructorInterceptor(
                    interceptorInstance, constructorMethods, sampleRate);
            Pointcut pointcut = new Pointcut(classMatcher, constructorMatcher);
            String defName = "annotation-" + clazz.getSimpleName() + "-<init>";
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, constructorInterceptor, priority);
            registry.register(definition);
            log.debug("Registered constructor interceptor: {}", defName);
        }

        // --- Handle @OnMethodPattern methods (regex matching) ---
        for (Map.Entry<String, List<Method>> entry : methodPatternMethods.entrySet()) {
            String regex = entry.getKey();
            List<Method> methods = entry.getValue();
            MethodMatcher methodMatcher = MethodMatcher.byNamePattern(regex);
            Interceptor interceptor = createPatternInterceptor(interceptorInstance, methods, sampleRate);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            String defName = "annotation-" + clazz.getSimpleName() + "-pattern-" + regex;
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor, priority);
            registry.register(definition);
            log.debug("Registered pattern interceptor: {} -> {}", defName, regex);
        }

        // --- Handle @WhenAnnotated methods (annotation matching) ---
        for (Map.Entry<String, List<Method>> entry : whenAnnotatedMethods.entrySet()) {
            String annotationClassName = entry.getKey();
            List<Method> methods = entry.getValue();
            MethodMatcher methodMatcher = MethodMatcher.byAnnotation(annotationClassName);
            Interceptor interceptor = createPatternInterceptor(interceptorInstance, methods, sampleRate);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            String defName = "annotation-" + clazz.getSimpleName() + "-annotated-" + annotationClassName;
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor, priority);
            registry.register(definition);
            log.debug("Registered annotation-matched interceptor: {} -> @{}", defName, annotationClassName);
        }

        // --- Collect all target method keys for standard advice ---
        Set<String> allTargetMethods = new HashSet<>();
        allTargetMethods.addAll(beforeMethods.keySet());
        allTargetMethods.addAll(afterMethods.keySet());
        allTargetMethods.addAll(aroundMethods.keySet());
        allTargetMethods.addAll(onExceptionMethods.keySet());
        allTargetMethods.addAll(afterReturningMethods.keySet());
        allTargetMethods.addAll(retryMethods.keySet());

        // Standard mode: one interceptor per target method
        if (allTargetMethods.isEmpty()) {
            return; // constructor/pattern/annotation interceptors already registered above
        }

        for (String targetMethod : allTargetMethods) {
            List<Method> befores = beforeMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> afters = afterMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> arounds = aroundMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> onExceptions = onExceptionMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> afterReturnings = afterReturningMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> retries = retryMethods.getOrDefault(targetMethod, Collections.emptyList());

            // Parse parameterTypes from the method key for overloaded method matching
            MethodMatcher methodMatcher = buildMethodMatcher(targetMethod);

            Interceptor interceptor = createReflectiveInterceptor(interceptorInstance,
                    befores, afters, arounds, onExceptions, afterReturnings,
                    sampleRate, isTimed, timedAnnotation);

            // Wrap with retry logic if @RetryOnException is present
            if (!retries.isEmpty()) {
                interceptor = wrapWithRetry(interceptor, retries, interceptorInstance);
            }

            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            String defName = "annotation-" + clazz.getSimpleName() + "-" + targetMethod;
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor, priority);
            registry.register(definition);
        }
    }

    // ==================== EnableIf / SampleRate / Timed helpers ====================

    private boolean checkEnableIfCondition(Class<?> clazz) {
        EnableIf enableIf = clazz.getAnnotation(EnableIf.class);
        if (enableIf == null) {
            return true; // no condition, always enabled
        }

        String havingValue = enableIf.havingValue();

        // Check system property first
        if (!enableIf.property().isEmpty()) {
            String propValue = System.getProperty(enableIf.property());
            if (propValue != null) {
                return havingValue.equals(propValue);
            }
        }

        // Check environment variable
        if (!enableIf.env().isEmpty()) {
            String envValue = System.getenv(enableIf.env());
            if (envValue != null) {
                return havingValue.equals(envValue);
            }
        }

        // Neither found — use matchIfMissing
        return enableIf.matchIfMissing();
    }

    private double getSampleRate(Class<?> clazz) {
        SampleRate sampleRate = clazz.getAnnotation(SampleRate.class);
        if (sampleRate == null) {
            return 1.0; // no sampling, always intercept
        }
        return sampleRate.value();
    }

    // ==================== Method key helpers ====================

    /**
     * Build a composite key from method name and parameter types.
     * "save" → "save"
     * "save" + ["java.lang.String", "int"] → "save(java.lang.String,int)"
     */
    private String methodKey(String methodName, String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return methodName;
        }
        return methodName + "(" + String.join(",", parameterTypes) + ")";
    }

    /**
     * Build a MethodMatcher from a composite key.
     * "save" → MethodMatcher.byName("save")
     * "save(java.lang.String,int)" → MethodMatcher.bySignature("save", "java.lang.String,int")
     */
    private MethodMatcher buildMethodMatcher(String key) {
        int parenIdx = key.indexOf('(');
        if (parenIdx < 0) {
            return MethodMatcher.byName(key);
        }
        String methodName = key.substring(0, parenIdx);
        String paramPart = key.substring(parenIdx + 1, key.length() - 1);
        return MethodMatcher.bySignature(methodName, paramPart);
    }

    // ==================== Interceptor factories ====================

    private Interceptor createReflectiveInterceptor(Object instance,
                                                    List<Method> befores, List<Method> afters,
                                                    List<Method> arounds, List<Method> onExceptions,
                                                    List<Method> afterReturnings,
                                                    double sampleRate, boolean isTimed,
                                                    Timed timedAnnotation) {
        return new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                if (!shouldSample(sampleRate)) return;
                if (isTimed) {
                    invocation.setAttachment("timed.startNanos", System.nanoTime());
                }
                invokeMethods(instance, arounds, invocation);
                invokeMethods(instance, befores, invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
                if (!shouldSample(sampleRate)) return;
                invokeMethods(instance, arounds, invocation);
                invokeMethods(instance, afters, invocation);
                // @AfterReturning methods are only called on successful return (not on exception)
                invokeMethods(instance, afterReturnings, invocation);
                if (isTimed) {
                    recordTiming(invocation, timedAnnotation);
                }
            }

            @Override
            public void onException(MethodInvocation invocation) {
                if (!shouldSample(sampleRate)) return;
                invokeMethods(instance, arounds, invocation);
                // Filter @OnException by exceptionType
                invokeOnExceptionFiltered(instance, onExceptions, invocation);
                if (isTimed) {
                    recordTiming(invocation, timedAnnotation);
                }
            }

            private boolean shouldSample(double rate) {
                return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
            }
        };
    }

    /**
     * Creates an interceptor for @OnMethodPattern and @WhenAnnotated methods.
     * These methods are invoked in the before phase.
     */
    private Interceptor createPatternInterceptor(Object instance, List<Method> methods, double sampleRate) {
        return new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                if (!shouldSample(sampleRate)) return;
                invokeMethods(instance, methods, invocation);
            }

            private boolean shouldSample(double rate) {
                return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
            }
        };
    }

    /**
     * Creates an interceptor for @OnConstructor methods.
     */
    private Interceptor createConstructorInterceptor(Object instance, List<Method> constructorMethods, double sampleRate) {
        return new Interceptor() {
            @Override
            public void after(MethodInvocation invocation) {
                if (!shouldSample(sampleRate)) return;
                // Constructor "returns" the new instance
                invokeMethods(instance, constructorMethods, invocation);
            }

            private boolean shouldSample(double rate) {
                return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
            }
        };
    }

    /**
     * Wraps an interceptor with retry logic from @RetryOnException.
     */
    private Interceptor wrapWithRetry(Interceptor delegate, List<Method> retryMethods, Object instance) {
        // Extract retry config from the first @RetryOnException method
        RetryOnException retryAnn = retryMethods.get(0).getAnnotation(RetryOnException.class);
        int maxRetries = retryAnn.maxRetries();
        long delayMs = retryAnn.delayMs();
        Set<Class<? extends Throwable>> retryFor = new HashSet<>(Arrays.asList(retryAnn.retryFor()));

        return new Interceptor() {
            private final AtomicLong retryAttempt = new AtomicLong(0);

            @Override
            public void before(MethodInvocation invocation) {
                retryAttempt.set(0);
                delegate.before(invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
                retryAttempt.set(0); // reset on success
                delegate.after(invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
                Throwable t = invocation.getThrowable();
                // Check if this exception type should be retried
                if (t != null && shouldRetryFor(t, retryFor)) {
                    long attempt = retryAttempt.incrementAndGet();
                    if (attempt <= maxRetries) {
                        log.info("Retrying {} (attempt {}/{})",
                                invocation.getMethodName(), attempt, maxRetries);
                        if (delayMs > 0) {
                            try { Thread.sleep(delayMs); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        // Notify retry methods
                        invocation.setAttachment("retry.attempt", attempt);
                        invokeMethods(instance, retryMethods, invocation);
                        // Mark the exception as suppressed to trigger re-execution
                        // The actual retry is handled by the framework
                        invocation.suppressException();
                        return;
                    }
                }
                retryAttempt.set(0);
                delegate.onException(invocation);
            }

            private boolean shouldRetryFor(Throwable t, Set<Class<? extends Throwable>> retryFor) {
                if (retryFor.isEmpty()) return true; // retry for all exceptions
                for (Class<? extends Throwable> exType : retryFor) {
                    if (exType.isInstance(t)) return true;
                }
                return false;
            }
        };
    }

    // ==================== Utility methods ====================

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

    /**
     * Invoke @OnException methods, filtering by exceptionType.
     */
    private void invokeOnExceptionFiltered(Object inst, List<Method> onExceptions, MethodInvocation inv) {
        Throwable thrown = inv.getThrowable();
        for (Method m : onExceptions) {
            OnException ann = m.getAnnotation(OnException.class);
            if (ann != null && ann.exceptionType() != Throwable.class) {
                // Filter by exception type
                if (thrown != null && !ann.exceptionType().isInstance(thrown)) {
                    continue; // skip — exception doesn't match the filter
                }
            }
            try {
                m.setAccessible(true);
                m.invoke(inst, inv);
            } catch (Exception e) {
                log.warn("Error invoking onException method {}: {}", m.getName(), e.getMessage());
            }
        }
    }

    private void recordTiming(MethodInvocation invocation, Timed timedAnnotation) {
        Long startNanos = (Long) invocation.getAttachment("timed.startNanos");
        if (startNanos != null) {
            long elapsed = System.nanoTime() - startNanos;
            long elapsedMs = elapsed / 1_000_000;
            invocation.setAttachment("timed.elapsedNanos", elapsed);
            invocation.setAttachment("timed.elapsedMs", elapsedMs);

            if (timedAnnotation != null && timedAnnotation.log()) {
                String label = timedAnnotation.label().isEmpty()
                        ? invocation.getMethodName() : timedAnnotation.label();
                log.info("[TIMED] {} took {}ms", label, elapsedMs);
            }
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
}
