package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutParser;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans classes for weaver-girl annotations and registers them as interceptor definitions.
 *
 * <p>Supports 40+ annotations across 12 dimensions:
 *
 * <ul>
 *   <li><b>Lifecycle:</b> @Before, @After, @Around, @OnException, @AfterReturning,
 *       @AfterThrowing, @AfterFinally, @RewriteArg, @OnConstructor, @OnFieldGet, @OnFieldSet,
 *       @OnStaticInit, @OnCatch
 *   <li><b>Matching:</b> @WeaveClass, @OnMethodPattern, @WhenAnnotated, @Pointcut
 *   <li><b>Ordering:</b> @Order, @DeclarePrecedence
 *   <li><b>Condition:</b> @EnableIf, @SampleRate
 *   <li><b>Observability:</b> @Timed, @Trace, @Tag, @Counted, @Logged, @Metric, @Histogram, @Gauge
 *   <li><b>Resilience:</b> @RetryOnException, @CircuitBreaker, @Timeout, @Fallback, @Bulkhead, @RateLimiter
 *   <li><b>Caching:</b> @CacheResult, @CacheEvict
 *   <li><b>Security:</b> @RequiresRole, @Audited
 *   <li><b>Concurrency:</b> @Synchronized, @ReadOnly, @Idempotent
 *   <li><b>Context:</b> @Arg, @Return, @This, @Origin, @Elapsed
 *   <li><b>Validation:</b> @ValidateArgs, @ValidateReturn, @NotNull
 * </ul>
 *
 * @since 1.0.0
 */
public class AnnotationPluginLoader {

  private static final Logger log = LoggerFactory.getLogger(AnnotationPluginLoader.class);

  // Circuit breaker state: key = "className#methodName", value = state
  private static final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
  // Bulkhead counters: key = "className#methodName", value = current count
  private static final Map<String, AtomicInteger> bulkheadCounters = new ConcurrentHashMap<>();
  // Rate limiter state: key = "className#methodName", value = last check time + tokens
  private static final Map<String, RateLimitState> rateLimitStates = new ConcurrentHashMap<>();
  // Simple in-memory cache: key = cacheKey, value = {value, expiryNanos}
  private static final Map<String, CacheEntry> cacheStore = new ConcurrentHashMap<>();
  // Idempotency store: key = idempotencyKey, value = return value
  private static final Map<String, Object> idempotencyStore = new ConcurrentHashMap<>();
  // Attachment key used to resolve @Elapsed advice-method parameters
  private static final String ELAPSED_START_ATTACHMENT = "__weavergirl.elapsed.startNanos";
  // Synchronized locks
  private static final Map<String, Object> lockObjects = new ConcurrentHashMap<>();

  /** Scan a set of annotated classes and register their interceptors. */
  public void loadAnnotatedInterceptors(
      Set<Class<?>> annotatedClasses, InterceptorRegistry registry) {
    for (Class<?> clazz : annotatedClasses) {
      try {
        loadFromAnnotatedClass(clazz, registry);
      } catch (Exception e) {
        log.error(
            "Failed to load annotated interceptor class {}: {}",
            clazz.getName(),
            e.getMessage(),
            e);
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

    // Read @Order priority
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
    Map<String, List<Method>> afterThrowingMethods = new HashMap<>();
    Map<String, List<Method>> afterFinallyMethods = new HashMap<>();
    Map<String, List<Method>> rewriteArgMethods = new HashMap<>();
    Map<String, List<Method>> retryMethods = new HashMap<>();
    Map<String, List<Method>> traceMethods = new HashMap<>();
    Map<String, List<Method>> tagMethods = new HashMap<>();
    Map<String, List<Method>> countedMethods = new HashMap<>();
    Map<String, List<Method>> loggedMethods = new HashMap<>();
    Map<String, List<Method>> metricMethods = new HashMap<>();
    Map<String, List<Method>> histogramMethods = new HashMap<>();
    Map<String, List<Method>> gaugeMethods = new HashMap<>();
    Map<String, List<Method>> circuitBreakerMethods = new HashMap<>();
    Map<String, List<Method>> timeoutMethods = new HashMap<>();
    Map<String, List<Method>> fallbackMethods = new HashMap<>();
    Map<String, List<Method>> bulkheadMethods = new HashMap<>();
    Map<String, List<Method>> rateLimiterMethods = new HashMap<>();
    Map<String, List<Method>> cacheResultMethods = new HashMap<>();
    Map<String, List<Method>> cacheEvictMethods = new HashMap<>();
    Map<String, List<Method>> requiresRoleMethods = new HashMap<>();
    Map<String, List<Method>> auditedMethods = new HashMap<>();
    Map<String, List<Method>> synchronizedMethods = new HashMap<>();
    Map<String, List<Method>> readOnlyMethods = new HashMap<>();
    Map<String, List<Method>> idempotentMethods = new HashMap<>();
    Map<String, List<Method>> validateArgsMethods = new HashMap<>();
    Map<String, List<Method>> validateReturnMethods = new HashMap<>();
    Map<String, List<Method>> methodPatternMethods = new HashMap<>();
    Map<String, List<Method>> whenAnnotatedMethods = new HashMap<>();
    List<Method> constructorMethods = new ArrayList<>();
    List<Method> fieldGetMethods = new ArrayList<>();
    List<Method> fieldSetMethods = new ArrayList<>();
    List<Method> staticInitMethods = new ArrayList<>();
    List<Method> catchMethods = new ArrayList<>();

    // Collect named @Pointcut definitions: methodName -> PointcutExpression
    Map<String, PointcutExpression> namedPointcuts = new HashMap<>();
    for (Method m : clazz.getDeclaredMethods()) {
      if (m.isAnnotationPresent(com.github.cc11001100.weavergirl.annotation.Pointcut.class)) {
        com.github.cc11001100.weavergirl.annotation.Pointcut ann = m.getAnnotation(com.github.cc11001100.weavergirl.annotation.Pointcut.class);
        namedPointcuts.put(m.getName(), PointcutParser.getInstance().parse(ann.value()));
      }
    }

    for (Method m : clazz.getDeclaredMethods()) {
      // Lifecycle annotations
      if (m.isAnnotationPresent(Before.class)) {
        Before ann = m.getAnnotation(Before.class);
        beforeMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(After.class)) {
        After ann = m.getAnnotation(After.class);
        afterMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(Around.class)) {
        Around ann = m.getAnnotation(Around.class);
        aroundMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(OnException.class)) {
        OnException ann = m.getAnnotation(OnException.class);
        onExceptionMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(AfterReturning.class)) {
        AfterReturning ann = m.getAnnotation(AfterReturning.class);
        afterReturningMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(AfterThrowing.class)) {
        AfterThrowing ann = m.getAnnotation(AfterThrowing.class);
        afterThrowingMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(AfterFinally.class)) {
        AfterFinally ann = m.getAnnotation(AfterFinally.class);
        afterFinallyMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(RewriteArg.class)) {
        RewriteArg ann = m.getAnnotation(RewriteArg.class);
        rewriteArgMethods
            .computeIfAbsent(resolvePointcutKey(ann, namedPointcuts), k -> new ArrayList<>())
            .add(m);
      }
      if (m.isAnnotationPresent(OnConstructor.class)) {
        constructorMethods.add(m);
      }
      if (m.isAnnotationPresent(OnFieldGet.class)) {
        fieldGetMethods.add(m);
      }
      if (m.isAnnotationPresent(OnFieldSet.class)) {
        fieldSetMethods.add(m);
      }
      if (m.isAnnotationPresent(OnStaticInit.class)) {
        staticInitMethods.add(m);
      }
      if (m.isAnnotationPresent(OnCatch.class)) {
        catchMethods.add(m);
      }

      // Matching annotations
      if (m.isAnnotationPresent(OnMethodPattern.class)) {
        OnMethodPattern ann = m.getAnnotation(OnMethodPattern.class);
        methodPatternMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(WhenAnnotated.class)) {
        WhenAnnotated ann = m.getAnnotation(WhenAnnotated.class);
        whenAnnotatedMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }

      // Observability annotations
      if (m.isAnnotationPresent(Trace.class)) {
        Trace ann = m.getAnnotation(Trace.class);
        traceMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Tag.class)) {
        Tag ann = m.getAnnotation(Tag.class);
        tagMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Counted.class)) {
        Counted ann = m.getAnnotation(Counted.class);
        countedMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Logged.class)) {
        Logged ann = m.getAnnotation(Logged.class);
        loggedMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Metric.class)) {
        Metric ann = m.getAnnotation(Metric.class);
        metricMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Histogram.class)) {
        Histogram ann = m.getAnnotation(Histogram.class);
        histogramMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Gauge.class)) {
        Gauge ann = m.getAnnotation(Gauge.class);
        gaugeMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }

      // Resilience annotations
      if (m.isAnnotationPresent(RetryOnException.class)) {
        RetryOnException ann = m.getAnnotation(RetryOnException.class);
        retryMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(CircuitBreaker.class)) {
        CircuitBreaker ann = m.getAnnotation(CircuitBreaker.class);
        circuitBreakerMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Timeout.class)) {
        Timeout ann = m.getAnnotation(Timeout.class);
        timeoutMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Fallback.class)) {
        Fallback ann = m.getAnnotation(Fallback.class);
        fallbackMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Bulkhead.class)) {
        Bulkhead ann = m.getAnnotation(Bulkhead.class);
        bulkheadMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(RateLimiter.class)) {
        RateLimiter ann = m.getAnnotation(RateLimiter.class);
        rateLimiterMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }

      // Caching annotations
      if (m.isAnnotationPresent(CacheResult.class)) {
        CacheResult ann = m.getAnnotation(CacheResult.class);
        cacheResultMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(CacheEvict.class)) {
        CacheEvict ann = m.getAnnotation(CacheEvict.class);
        cacheEvictMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }

      // Security annotations
      if (m.isAnnotationPresent(RequiresRole.class)) {
        RequiresRole ann = m.getAnnotation(RequiresRole.class);
        requiresRoleMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Audited.class)) {
        Audited ann = m.getAnnotation(Audited.class);
        auditedMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }

      // Concurrency annotations
      if (m.isAnnotationPresent(Synchronized.class)) {
        Synchronized ann = m.getAnnotation(Synchronized.class);
        synchronizedMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(ReadOnly.class)) {
        ReadOnly ann = m.getAnnotation(ReadOnly.class);
        readOnlyMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(Idempotent.class)) {
        Idempotent ann = m.getAnnotation(Idempotent.class);
        idempotentMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }

      // Validation annotations
      if (m.isAnnotationPresent(ValidateArgs.class)) {
        ValidateArgs ann = m.getAnnotation(ValidateArgs.class);
        validateArgsMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
      if (m.isAnnotationPresent(ValidateReturn.class)) {
        ValidateReturn ann = m.getAnnotation(ValidateReturn.class);
        validateReturnMethods.computeIfAbsent(ann.value(), k -> new ArrayList<>()).add(m);
      }
    }

    // --- PointcutExpression mode ---
    boolean usePointcutExpression = !weaveClass.pointcut().isEmpty();
    if (usePointcutExpression) {
      if (!rewriteArgMethods.isEmpty()
          || !constructorMethods.isEmpty()
          || !fieldGetMethods.isEmpty()
          || !fieldSetMethods.isEmpty()) {
        log.warn(
            "Interceptor {} uses @WeaveClass(pointcut=...): @RewriteArg/@OnConstructor/@OnFieldGet/@OnFieldSet"
                + " are only supported with explicit target matching and will be ignored",
            clazz.getSimpleName());
      }
      PointcutExpression expression = PointcutParser.getInstance().parse(weaveClass.pointcut());
      Pointcut pointcut = expression.toPointcut();
      Interceptor interceptor =
          createReflectiveInterceptor(
              interceptorInstance,
              flattenAll(beforeMethods),
              flattenAll(afterMethods),
              flattenAll(aroundMethods),
              flattenAll(onExceptionMethods),
              flattenAll(afterReturningMethods),
              flattenAll(afterThrowingMethods),
              flattenAll(afterFinallyMethods),
              sampleRate,
              isTimed,
              timedAnnotation);
      String defName = "annotation-" + clazz.getSimpleName();
      InterceptorDefinition definition =
          new InterceptorDefinition(defName, pointcut, interceptor, priority);
      registry.register(definition);
      return;
    }

    // --- Build ClassMatcher ---
    ClassMatcher classMatcher = buildClassMatcherFromAnnotation(weaveClass);

    // --- Register field interceptors ---
    if (!fieldGetMethods.isEmpty()) {
      for (Method m : fieldGetMethods) {
        OnFieldGet ann = m.getAnnotation(OnFieldGet.class);
        MethodMatcher fieldMatcher = MethodMatcher.byName(ann.value());
        Interceptor interceptor = createFieldGetInterceptor(interceptorInstance, m, sampleRate);
        Pointcut pointcut = new Pointcut(classMatcher, fieldMatcher);
        String defName = "annotation-" + clazz.getSimpleName() + "-fieldGet-" + ann.value();
        registry.register(new InterceptorDefinition(defName, pointcut, interceptor, priority));
      }
    }
    if (!fieldSetMethods.isEmpty()) {
      for (Method m : fieldSetMethods) {
        OnFieldSet ann = m.getAnnotation(OnFieldSet.class);
        MethodMatcher fieldMatcher = MethodMatcher.byName(ann.value());
        Interceptor interceptor = createFieldSetInterceptor(interceptorInstance, m, sampleRate);
        Pointcut pointcut = new Pointcut(classMatcher, fieldMatcher);
        String defName = "annotation-" + clazz.getSimpleName() + "-fieldSet-" + ann.value();
        registry.register(new InterceptorDefinition(defName, pointcut, interceptor, priority));
      }
    }

    // --- Register static init interceptor ---
    if (!staticInitMethods.isEmpty()) {
      MethodMatcher staticInitMatcher = MethodMatcher.byName("<clinit>");
      Interceptor interceptor =
          createStaticInitInterceptor(interceptorInstance, staticInitMethods, sampleRate);
      Pointcut pointcut = new Pointcut(classMatcher, staticInitMatcher);
      String defName = "annotation-" + clazz.getSimpleName() + "-<clinit>";
      registry.register(new InterceptorDefinition(defName, pointcut, interceptor, priority));
    }

    // --- Register constructor interceptor ---
    // @OnConstructor(parameterTypes=...) selects a specific overload; methods with an empty
    // parameterTypes match all constructors. Each distinct signature gets its own definition so
    // the CONSTRUCTOR matcher filters overloads at transform time.
    if (!constructorMethods.isEmpty()) {
      Map<String, List<Method>> ctorBySignature = new HashMap<>();
      for (Method m : constructorMethods) {
        OnConstructor ann = m.getAnnotation(OnConstructor.class);
        String[] parameterTypes = ann != null ? ann.parameterTypes() : new String[0];
        String key =
            parameterTypes.length == 0 ? "<init>" : "<init>(" + String.join(",", parameterTypes) + ")";
        ctorBySignature.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
      }
      for (Map.Entry<String, List<Method>> entry : ctorBySignature.entrySet()) {
        // Must use CONSTRUCTOR matchers (not byName/bySignature): WeaverTransformer only
        // routes CONSTRUCTOR-typed definitions to ConstructorAdvice. A byName("<init>")
        // definition would be inlined with InterceptAdvice, whose @Advice.Origin Method
        // cannot bind to a constructor, so the transformation would fail.
        String key = entry.getKey();
        int parenIdx = key.indexOf('(');
        MethodMatcher constructorMatcher =
            parenIdx < 0
                ? MethodMatcher.byConstructor()
                : MethodMatcher.byConstructor(key.substring(parenIdx + 1, key.length() - 1));
        Interceptor interceptor =
            createConstructorInterceptor(interceptorInstance, entry.getValue(), sampleRate);
        Pointcut pointcut = new Pointcut(classMatcher, constructorMatcher);
        String defName = "annotation-" + clazz.getSimpleName() + "-" + entry.getKey();
        registry.register(new InterceptorDefinition(defName, pointcut, interceptor, priority));
      }
    }

    // --- Register catch-block interceptors ---
    for (Method m : catchMethods) {
      registerCatchInterceptor(interceptorInstance, m, classMatcher, clazz, priority, registry);
    }

    // --- Register argument-rewrite interceptors ---
    // Each @RewriteArg method group becomes an ARGUMENT_REWRITE definition so the transformer
    // weaves AsyncArgumentAdvice (writable @Advice.Argument slots): setArgument(i, ...) reaches
    // the method body. Uses the same interceptor shape as the async-context-propagation plugin.
    for (Map.Entry<String, List<Method>> entry : rewriteArgMethods.entrySet()) {
      MethodMatcher methodMatcher = buildMethodMatcher(entry.getKey());
      Interceptor interceptor =
          createArgumentRewriteInterceptor(interceptorInstance, entry.getValue(), sampleRate);
      Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
      String defName = "annotation-" + clazz.getSimpleName() + "-rewrite-" + entry.getKey();
      registry.register(
          new InterceptorDefinition(
              defName,
              pointcut,
              interceptor,
              priority,
              InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE));
    }

    // --- Register pattern/annotation interceptors ---
    for (Map.Entry<String, List<Method>> entry : methodPatternMethods.entrySet()) {
      MethodMatcher methodMatcher = MethodMatcher.byNamePattern(entry.getKey());
      Interceptor interceptor =
          createPatternInterceptor(interceptorInstance, entry.getValue(), sampleRate);
      Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
      String defName = "annotation-" + clazz.getSimpleName() + "-pattern-" + entry.getKey();
      registry.register(new InterceptorDefinition(defName, pointcut, interceptor, priority));
    }
    for (Map.Entry<String, List<Method>> entry : whenAnnotatedMethods.entrySet()) {
      MethodMatcher methodMatcher = MethodMatcher.byAnnotation(entry.getKey());
      Interceptor interceptor =
          createPatternInterceptor(interceptorInstance, entry.getValue(), sampleRate);
      Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
      String defName = "annotation-" + clazz.getSimpleName() + "-annotated-" + entry.getKey();
      registry.register(new InterceptorDefinition(defName, pointcut, interceptor, priority));
    }

    // --- Collect all target method keys ---
    Set<String> allTargetMethods = new HashSet<>();
    allTargetMethods.addAll(beforeMethods.keySet());
    allTargetMethods.addAll(afterMethods.keySet());
    allTargetMethods.addAll(aroundMethods.keySet());
    allTargetMethods.addAll(onExceptionMethods.keySet());
    allTargetMethods.addAll(afterReturningMethods.keySet());
    allTargetMethods.addAll(afterThrowingMethods.keySet());
    allTargetMethods.addAll(afterFinallyMethods.keySet());
    allTargetMethods.addAll(retryMethods.keySet());
    allTargetMethods.addAll(traceMethods.keySet());
    allTargetMethods.addAll(tagMethods.keySet());
    allTargetMethods.addAll(countedMethods.keySet());
    allTargetMethods.addAll(loggedMethods.keySet());
    allTargetMethods.addAll(metricMethods.keySet());
    allTargetMethods.addAll(histogramMethods.keySet());
    allTargetMethods.addAll(gaugeMethods.keySet());
    allTargetMethods.addAll(circuitBreakerMethods.keySet());
    allTargetMethods.addAll(timeoutMethods.keySet());
    allTargetMethods.addAll(fallbackMethods.keySet());
    allTargetMethods.addAll(bulkheadMethods.keySet());
    allTargetMethods.addAll(rateLimiterMethods.keySet());
    allTargetMethods.addAll(cacheResultMethods.keySet());
    allTargetMethods.addAll(cacheEvictMethods.keySet());
    allTargetMethods.addAll(requiresRoleMethods.keySet());
    allTargetMethods.addAll(auditedMethods.keySet());
    allTargetMethods.addAll(synchronizedMethods.keySet());
    allTargetMethods.addAll(readOnlyMethods.keySet());
    allTargetMethods.addAll(idempotentMethods.keySet());
    allTargetMethods.addAll(validateArgsMethods.keySet());
    allTargetMethods.addAll(validateReturnMethods.keySet());

    if (allTargetMethods.isEmpty()) {
      return;
    }

    for (String targetMethod : allTargetMethods) {
      List<Method> befores = beforeMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> afters = afterMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> arounds = aroundMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> onExceptions =
          onExceptionMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> afterReturnings =
          afterReturningMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> afterThrowings =
          afterThrowingMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> afterFinallys =
          afterFinallyMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> retries = retryMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> traces = traceMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> tags = tagMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> counted = countedMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> logged = loggedMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> metrics = metricMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> histograms =
          histogramMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> gauges = gaugeMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> circuitBreakers =
          circuitBreakerMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> timeouts = timeoutMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> fallbacks = fallbackMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> bulkheads = bulkheadMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> rateLimiters =
          rateLimiterMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> cacheResults =
          cacheResultMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> cacheEvicts =
          cacheEvictMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> requiresRoles =
          requiresRoleMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> auditeds = auditedMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> syncs = synchronizedMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> readOnlys = readOnlyMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> idempotents =
          idempotentMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> validateArgss =
          validateArgsMethods.getOrDefault(targetMethod, Collections.emptyList());
      List<Method> validateReturns =
          validateReturnMethods.getOrDefault(targetMethod, Collections.emptyList());

      MethodMatcher methodMatcher = buildMethodMatcher(targetMethod);

      Interceptor interceptor =
          createReflectiveInterceptor(
              interceptorInstance,
              befores,
              afters,
              arounds,
              onExceptions,
              afterReturnings,
              afterThrowings,
              afterFinallys,
              sampleRate,
              isTimed,
              timedAnnotation);

      // Layer on additional behaviors (order matters!)
      if (!traces.isEmpty()) interceptor = wrapWithTrace(interceptor, traces, interceptorInstance);
      if (!tags.isEmpty()) interceptor = wrapWithTag(interceptor, tags, interceptorInstance);
      if (!counted.isEmpty())
        interceptor = wrapWithCounted(interceptor, counted, interceptorInstance);
      if (!logged.isEmpty()) interceptor = wrapWithLogged(interceptor, logged, interceptorInstance);
      if (!metrics.isEmpty())
        interceptor = wrapWithMetric(interceptor, metrics, interceptorInstance);
      if (!histograms.isEmpty())
        interceptor = wrapWithHistogram(interceptor, histograms, interceptorInstance);
      if (!gauges.isEmpty()) interceptor = wrapWithGauge(interceptor, gauges, interceptorInstance);
      if (!circuitBreakers.isEmpty())
        interceptor = wrapWithCircuitBreaker(interceptor, circuitBreakers, interceptorInstance);
      if (!timeouts.isEmpty())
        interceptor = wrapWithTimeout(interceptor, timeouts, interceptorInstance);
      if (!fallbacks.isEmpty())
        interceptor = wrapWithFallback(interceptor, fallbacks, interceptorInstance);
      if (!bulkheads.isEmpty())
        interceptor = wrapWithBulkhead(interceptor, bulkheads, interceptorInstance);
      if (!rateLimiters.isEmpty())
        interceptor = wrapWithRateLimiter(interceptor, rateLimiters, interceptorInstance);
      if (!cacheResults.isEmpty())
        interceptor = wrapWithCacheResult(interceptor, cacheResults, interceptorInstance);
      if (!cacheEvicts.isEmpty())
        interceptor = wrapWithCacheEvict(interceptor, cacheEvicts, interceptorInstance);
      if (!requiresRoles.isEmpty())
        interceptor = wrapWithRequiresRole(interceptor, requiresRoles, interceptorInstance);
      if (!auditeds.isEmpty())
        interceptor = wrapWithAudited(interceptor, auditeds, interceptorInstance);
      if (!syncs.isEmpty())
        interceptor = wrapWithSynchronized(interceptor, syncs, interceptorInstance);
      if (!readOnlys.isEmpty())
        interceptor = wrapWithReadOnly(interceptor, readOnlys, interceptorInstance);
      if (!idempotents.isEmpty())
        interceptor = wrapWithIdempotent(interceptor, idempotents, interceptorInstance);
      if (!validateArgss.isEmpty())
        interceptor = wrapWithValidateArgs(interceptor, validateArgss, interceptorInstance);
      if (!validateReturns.isEmpty())
        interceptor = wrapWithValidateReturn(interceptor, validateReturns, interceptorInstance);
      if (!retries.isEmpty())
        interceptor = wrapWithRetry(interceptor, retries, interceptorInstance);

      Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
      String defName = "annotation-" + clazz.getSimpleName() + "-" + targetMethod;
      registry.register(new InterceptorDefinition(defName, pointcut, interceptor, priority));
    }
  }

  // ==================== Wrapper methods for each annotation dimension ====================

  private Interceptor wrapWithTrace(Interceptor delegate, List<Method> methods, Object instance) {
    Trace ann = methods.get(0).getAnnotation(Trace.class);
    String spanName = ann.spanName().isEmpty() ? ann.value() : ann.spanName();
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        inv.setAttachment("trace.spanName", spanName);
        inv.setAttachment("trace.kind", ann.kind());
        inv.setAttachment("trace.startNanos", System.nanoTime());
        invokeMethods(instance, methods, inv);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        Long start = (Long) inv.getAttachment("trace.startNanos");
        if (start != null) {
          long elapsed = System.nanoTime() - start;
          inv.setAttachment("trace.elapsedNanos", elapsed);
        }
        invokeMethods(instance, methods, inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
        if (ann.recordException()) {
          inv.setAttachment("trace.exception", inv.getThrowable());
        }
        invokeMethods(instance, methods, inv);
      }
    };
  }

  private Interceptor wrapWithTag(Interceptor delegate, List<Method> methods, Object instance) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        for (Method m : methods) {
          Tag tag = m.getAnnotation(Tag.class);
          String tagValue = tag.tagValue();
          if (tagValue.isEmpty() && tag.argIndex() >= 0) {
            Object arg = inv.getArgument(tag.argIndex());
            tagValue = arg != null ? arg.toString() : "null";
          }
          inv.setAttachment("tag." + tag.key(), tagValue);
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        for (Method m : methods) {
          Tag tag = m.getAnnotation(Tag.class);
          if (tag.useReturn()) {
            inv.setAttachment("tag." + tag.key(), inv.getReturnValue());
          }
        }
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithCounted(Interceptor delegate, List<Method> methods, Object instance) {
    return new Interceptor() {
      private final AtomicLong counter = new AtomicLong(0);

      @Override
      public void before(MethodInvocation inv) {
        Counted ann = methods.get(0).getAnnotation(Counted.class);
        if (!ann.recordFailuresOnly()) {
          counter.incrementAndGet();
          inv.setAttachment("counted.value", counter.get());
        }
        invokeMethods(instance, methods, inv);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        Counted ann = methods.get(0).getAnnotation(Counted.class);
        counter.incrementAndGet();
        inv.setAttachment("counted.failure", true);
        inv.setAttachment("counted.value", counter.get());
        invokeMethods(instance, methods, inv);
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithLogged(Interceptor delegate, List<Method> methods, Object instance) {
    Logged ann = methods.get(0).getAnnotation(Logged.class);
    String prefix = ann.prefix().isEmpty() ? ann.value() : ann.prefix();
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        inv.setAttachment("logged.startNanos", System.nanoTime());
        if (ann.logArgs()) {
          log(ann.level(), "[{}] Entry — args: {}", prefix, Arrays.toString(inv.getArguments()));
        } else {
          log(ann.level(), "[{}] Entry", prefix);
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        logExit(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
        logExit(inv);
      }

      private void logExit(MethodInvocation inv) {
        StringBuilder sb = new StringBuilder("[").append(prefix).append("] Exit");
        if (ann.logTime()) {
          Long start = (Long) inv.getAttachment("logged.startNanos");
          if (start != null)
            sb.append(" — ").append((System.nanoTime() - start) / 1_000_000).append("ms");
        }
        if (ann.logResult() && inv.getReturnValue() != null) {
          sb.append(" — result: ").append(inv.getReturnValue());
        }
        if (inv.hasException()) {
          sb.append(" — EXCEPTION: ").append(inv.getThrowable().getMessage());
        }
        log(ann.level(), sb.toString());
      }

      private void log(String level, String msg, Object... args) {
        switch (level.toUpperCase()) {
          case "TRACE":
            log.trace(msg, args);
            break;
          case "DEBUG":
            log.debug(msg, args);
            break;
          case "INFO":
            log.info(msg, args);
            break;
          case "WARN":
            log.warn(msg, args);
            break;
          case "ERROR":
            log.error(msg, args);
            break;
          default:
            log.debug(msg, args);
        }
      }
    };
  }

  private Interceptor wrapWithMetric(Interceptor delegate, List<Method> methods, Object instance) {
    Metric ann = methods.get(0).getAnnotation(Metric.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        if (ann.argIndex() >= 0) {
          Object val = inv.getArgument(ann.argIndex());
          if (val instanceof Number) {
            inv.setAttachment("metric." + ann.name(), ((Number) val).doubleValue());
          }
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        if (ann.useReturn() && inv.getReturnValue() instanceof Number) {
          inv.setAttachment("metric." + ann.name(), ((Number) inv.getReturnValue()).doubleValue());
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithHistogram(
      Interceptor delegate, List<Method> methods, Object instance) {
    Histogram ann = methods.get(0).getAnnotation(Histogram.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        inv.setAttachment("histogram.startNanos", System.nanoTime());
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        recordHistogram(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
        recordHistogram(inv);
      }

      private void recordHistogram(MethodInvocation inv) {
        Long start = (Long) inv.getAttachment("histogram.startNanos");
        if (start != null) {
          long elapsedMs = (System.nanoTime() - start) / 1_000_000;
          inv.setAttachment(
              "histogram." + (ann.name().isEmpty() ? ann.value() : ann.name()) + ".elapsedMs",
              elapsedMs);
        }
      }
    };
  }

  private Interceptor wrapWithGauge(Interceptor delegate, List<Method> methods, Object instance) {
    Gauge ann = methods.get(0).getAnnotation(Gauge.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        recordGauge(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }

      private void recordGauge(MethodInvocation inv) {
        Object val = null;
        if (ann.argIndex() >= 0) val = inv.getArgument(ann.argIndex());
        else if (ann.useReturn()) val = inv.getReturnValue();
        if (val instanceof Number) {
          inv.setAttachment("gauge." + ann.name(), ((Number) val).doubleValue());
        }
      }
    };
  }

  private Interceptor wrapWithCircuitBreaker(
      Interceptor delegate, List<Method> methods, Object instance) {
    CircuitBreaker ann = methods.get(0).getAnnotation(CircuitBreaker.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        String key = circuitKey(inv);
        CircuitState state = circuitStates.computeIfAbsent(key, k -> new CircuitState());
        if (state.isOpen(ann.openTimeoutMs())) {
          inv.skipMethod();
          inv.setReturnValue(null);
          inv.setAttachment("circuitBreaker.open", true);
          invokeMethods(instance, methods, inv);
          return;
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        String key = circuitKey(inv);
        CircuitState state = circuitStates.get(key);
        if (state != null) state.recordSuccess(ann.successThreshold());
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
        String key = circuitKey(inv);
        CircuitState state = circuitStates.computeIfAbsent(key, k -> new CircuitState());
        Throwable t = inv.getThrowable();
        if (shouldCount(t, ann.failureTypes())) {
          state.recordFailure(ann.failureThreshold());
        }
      }

      private boolean shouldCount(Throwable t, Class<? extends Throwable>[] types) {
        if (types.length == 0) return true;
        for (Class<? extends Throwable> type : types) {
          if (type.isInstance(t)) return true;
        }
        return false;
      }
    };
  }

  private Interceptor wrapWithTimeout(Interceptor delegate, List<Method> methods, Object instance) {
    Timeout ann = methods.get(0).getAnnotation(Timeout.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        inv.setAttachment(
            "timeout.deadlineNanos", System.nanoTime() + ann.durationMs() * 1_000_000);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        Long deadline = (Long) inv.getAttachment("timeout.deadlineNanos");
        if (deadline != null && System.nanoTime() > deadline) {
          inv.setAttachment("timeout.exceeded", true);
          invokeMethods(instance, methods, inv);
        }
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithFallback(
      Interceptor delegate, List<Method> methods, Object instance) {
    Fallback ann = methods.get(0).getAnnotation(Fallback.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        Throwable t = inv.getThrowable();
        if (shouldFallback(t, ann.onExceptions())) {
          inv.setAttachment("fallback.method", ann.method());
          invokeMethods(instance, methods, inv);
          inv.suppressException();
        }
        delegate.onException(inv);
      }

      private boolean shouldFallback(Throwable t, Class<? extends Throwable>[] types) {
        if (types.length == 0) return true;
        for (Class<? extends Throwable> type : types) {
          if (type.isInstance(t)) return true;
        }
        return false;
      }
    };
  }

  private Interceptor wrapWithBulkhead(
      Interceptor delegate, List<Method> methods, Object instance) {
    Bulkhead ann = methods.get(0).getAnnotation(Bulkhead.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        String key = bulkheadKey(inv);
        AtomicInteger counter = bulkheadCounters.computeIfAbsent(key, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > ann.maxConcurrent()) {
          counter.decrementAndGet();
          inv.skipMethod();
          inv.setReturnValue(null);
          inv.setAttachment("bulkhead.rejected", true);
          invokeMethods(instance, methods, inv);
          return;
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        decrementBulkhead(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
        decrementBulkhead(inv);
      }

      private void decrementBulkhead(MethodInvocation inv) {
        String key = bulkheadKey(inv);
        AtomicInteger counter = bulkheadCounters.get(key);
        if (counter != null) counter.decrementAndGet();
      }
    };
  }

  private Interceptor wrapWithRateLimiter(
      Interceptor delegate, List<Method> methods, Object instance) {
    RateLimiter ann = methods.get(0).getAnnotation(RateLimiter.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        String key = rateLimitKey(inv);
        RateLimitState state =
            rateLimitStates.computeIfAbsent(key, k -> new RateLimitState(ann.permitsPerSecond()));
        if (!state.tryAcquire()) {
          inv.skipMethod();
          inv.setReturnValue(null);
          inv.setAttachment("rateLimiter.rejected", true);
          invokeMethods(instance, methods, inv);
          return;
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithCacheResult(
      Interceptor delegate, List<Method> methods, Object instance) {
    CacheResult ann = methods.get(0).getAnnotation(CacheResult.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        String cacheKey = buildCacheKey(ann, inv);
        CacheEntry entry = cacheStore.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
          inv.skipMethod();
          inv.setReturnValue(entry.value);
          inv.setAttachment("cache.hit", true);
          return;
        }
        inv.setAttachment("cache.key", cacheKey);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        String cacheKey = (String) inv.getAttachment("cache.key");
        if (cacheKey != null && inv.getReturnValue() != null) {
          long ttlNanos = ann.ttlMs() > 0 ? ann.ttlMs() * 1_000_000 : Long.MAX_VALUE;
          cacheStore.put(
              cacheKey, new CacheEntry(inv.getReturnValue(), System.nanoTime() + ttlNanos));
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithCacheEvict(
      Interceptor delegate, List<Method> methods, Object instance) {
    CacheEvict ann = methods.get(0).getAnnotation(CacheEvict.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        if (ann.beforeInvocation()) {
          evict(inv);
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        if (!ann.beforeInvocation()) {
          evict(inv);
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }

      private void evict(MethodInvocation inv) {
        String prefix = ann.keyPrefix().isEmpty() ? ann.value() : ann.keyPrefix();
        if (ann.allEntries()) {
          cacheStore.keySet().removeIf(k -> k.startsWith(prefix));
        } else {
          cacheStore.remove(prefix + ":" + Arrays.toString(inv.getArguments()));
        }
      }
    };
  }

  private Interceptor wrapWithRequiresRole(
      Interceptor delegate, List<Method> methods, Object instance) {
    RequiresRole ann = methods.get(0).getAnnotation(RequiresRole.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        Object principal = inv.getArgument(ann.principalArgIndex());
        inv.setAttachment("requiresRole.required", ann.role());
        inv.setAttachment("requiresRole.principal", principal);
        invokeMethods(instance, methods, inv);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithAudited(Interceptor delegate, List<Method> methods, Object instance) {
    Audited ann = methods.get(0).getAnnotation(Audited.class);
    String action = ann.action().isEmpty() ? ann.value() : ann.action();
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        inv.setAttachment("audit.action", action);
        inv.setAttachment("audit.timestamp", System.currentTimeMillis());
        if (ann.includeArgs()) {
          inv.setAttachment("audit.args", Arrays.toString(inv.getArguments()));
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        if (ann.includeResult()) {
          inv.setAttachment("audit.result", inv.getReturnValue());
        }
        invokeMethods(instance, methods, inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
        inv.setAttachment("audit.exception", inv.getThrowable().getMessage());
        invokeMethods(instance, methods, inv);
      }
    };
  }

  private Interceptor wrapWithSynchronized(
      Interceptor delegate, List<Method> methods, Object instance) {
    Synchronized ann = methods.get(0).getAnnotation(Synchronized.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        String lockKey = ann.lockKey().isEmpty() ? inv.getTargetClass().getName() : ann.lockKey();
        Object lock = lockObjects.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
          delegate.before(inv);
        }
      }

      @Override
      public void after(MethodInvocation inv) {
        String lockKey = ann.lockKey().isEmpty() ? inv.getTargetClass().getName() : ann.lockKey();
        Object lock = lockObjects.get(lockKey);
        if (lock != null) {
          synchronized (lock) {
            delegate.after(inv);
          }
        } else {
          delegate.after(inv);
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        String lockKey = ann.lockKey().isEmpty() ? inv.getTargetClass().getName() : ann.lockKey();
        Object lock = lockObjects.get(lockKey);
        if (lock != null) {
          synchronized (lock) {
            delegate.onException(inv);
          }
        } else {
          delegate.onException(inv);
        }
      }
    };
  }

  private Interceptor wrapWithReadOnly(
      Interceptor delegate, List<Method> methods, Object instance) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        inv.setAttachment("readOnly", true);
        invokeMethods(instance, methods, inv);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithIdempotent(
      Interceptor delegate, List<Method> methods, Object instance) {
    Idempotent ann = methods.get(0).getAnnotation(Idempotent.class);
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        String key = buildIdempotencyKey(ann, inv);
        Object cached = idempotencyStore.get(key);
        if (cached != null) {
          inv.skipMethod();
          inv.setReturnValue(cached);
          inv.setAttachment("idempotent.cached", true);
          return;
        }
        inv.setAttachment("idempotent.key", key);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        String key = (String) inv.getAttachment("idempotent.key");
        if (key != null && inv.getReturnValue() != null) {
          idempotencyStore.put(key, inv.getReturnValue());
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithValidateArgs(
      Interceptor delegate, List<Method> methods, Object instance) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        for (Method m : methods) {
          ValidateArgs ann = m.getAnnotation(ValidateArgs.class);
          for (String rule : ann.rules()) {
            boolean valid = evaluateRule(rule, inv);
            if (!valid) {
              inv.skipMethod();
              inv.setReturnValue(null);
              inv.setAttachment("validateArgs.failed", ann.message());
              invokeMethods(instance, methods, inv);
              return;
            }
          }
        }
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  private Interceptor wrapWithValidateReturn(
      Interceptor delegate, List<Method> methods, Object instance) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        delegate.after(inv);
        for (Method m : methods) {
          ValidateReturn ann = m.getAnnotation(ValidateReturn.class);
          for (String rule : ann.rules()) {
            boolean valid = evaluateReturnRule(rule, inv);
            if (!valid) {
              inv.setAttachment("validateReturn.failed", ann.message());
              invokeMethods(instance, methods, inv);
              return;
            }
          }
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        delegate.onException(inv);
      }
    };
  }

  // ==================== Simple rule evaluation ====================

  private boolean evaluateRule(String rule, MethodInvocation inv) {
    // Support: "arg[0] != null", "arg[1] > 0", "arg[2].length() < 100"
    if (rule.contains("arg[")) {
      int start = rule.indexOf("arg[") + 4;
      int end = rule.indexOf(']', start);
      int idx = Integer.parseInt(rule.substring(start, end));
      Object arg = inv.getArgument(idx);
      String condition = rule.substring(end + 1).trim();
      return evaluateCondition(arg, condition);
    }
    return true;
  }

  private boolean evaluateReturnRule(String rule, MethodInvocation inv) {
    if (rule.startsWith("result")) {
      String condition = rule.substring(6).trim();
      return evaluateCondition(inv.getReturnValue(), condition);
    }
    return true;
  }

  private boolean evaluateCondition(Object value, String condition) {
    if (condition.equals("!= null")) return value != null;
    if (condition.equals("== null")) return value == null;
    if (condition.startsWith("> ") && value instanceof Number) {
      return ((Number) value).doubleValue() > Double.parseDouble(condition.substring(2));
    }
    if (condition.startsWith("< ") && value instanceof Number) {
      return ((Number) value).doubleValue() < Double.parseDouble(condition.substring(2));
    }
    if (condition.startsWith(">= ") && value instanceof Number) {
      return ((Number) value).doubleValue() >= Double.parseDouble(condition.substring(3));
    }
    if (condition.startsWith("<= ") && value instanceof Number) {
      return ((Number) value).doubleValue() <= Double.parseDouble(condition.substring(3));
    }
    return true;
  }

  // ==================== Key builders ====================

  private String circuitKey(MethodInvocation inv) {
    return inv.getTargetClass().getName() + "#" + inv.getMethodName();
  }

  private String bulkheadKey(MethodInvocation inv) {
    return inv.getTargetClass().getName() + "#" + inv.getMethodName();
  }

  private String rateLimitKey(MethodInvocation inv) {
    return inv.getTargetClass().getName() + "#" + inv.getMethodName();
  }

  private String buildCacheKey(CacheResult ann, MethodInvocation inv) {
    String prefix = ann.keyPrefix().isEmpty() ? ann.value() : ann.keyPrefix();
    if (ann.keyArgIndices().length > 0) {
      StringBuilder sb = new StringBuilder(prefix).append(":");
      for (int idx : ann.keyArgIndices()) {
        sb.append(inv.getArgument(idx)).append(",");
      }
      return sb.toString();
    }
    return prefix + ":" + Arrays.toString(inv.getArguments());
  }

  private String buildIdempotencyKey(Idempotent ann, MethodInvocation inv) {
    String base = inv.getTargetClass().getName() + "#" + inv.getMethodName();
    if (ann.keyArgIndices().length > 0) {
      StringBuilder sb = new StringBuilder(base).append(":");
      for (int idx : ann.keyArgIndices()) {
        sb.append(inv.getArgument(idx)).append(",");
      }
      return sb.toString();
    }
    return base + ":" + Arrays.toString(inv.getArguments());
  }

  // ==================== EnableIf / SampleRate helpers ====================

  private boolean checkEnableIfCondition(Class<?> clazz) {
    EnableIf enableIf = clazz.getAnnotation(EnableIf.class);
    if (enableIf == null) return true;
    String havingValue = enableIf.havingValue();
    if (!enableIf.property().isEmpty()) {
      String propValue = System.getProperty(enableIf.property());
      if (propValue != null) return havingValue.equals(propValue);
    }
    if (!enableIf.env().isEmpty()) {
      String envValue = System.getenv(enableIf.env());
      if (envValue != null) return havingValue.equals(envValue);
    }
    return enableIf.matchIfMissing();
  }

  private double getSampleRate(Class<?> clazz) {
    SampleRate sampleRate = clazz.getAnnotation(SampleRate.class);
    return sampleRate != null ? sampleRate.value() : 1.0;
  }

  // ==================== Method key helpers ====================

  private String methodKey(String methodName, String[] parameterTypes) {
    if (parameterTypes == null || parameterTypes.length == 0) return methodName;
    return methodName + "(" + String.join(",", parameterTypes) + ")";
  }

  private MethodMatcher buildMethodMatcher(String key) {
    int parenIdx = key.indexOf('(');
    if (parenIdx < 0) return MethodMatcher.byName(key);
    String methodName = key.substring(0, parenIdx);
    String paramPart = key.substring(parenIdx + 1, key.length() - 1);
    return MethodMatcher.bySignature(methodName, paramPart);
  }

  // ==================== Interceptor factories ====================

  private Interceptor createReflectiveInterceptor(
      Object instance,
      List<Method> befores,
      List<Method> afters,
      List<Method> arounds,
      List<Method> onExceptions,
      List<Method> afterReturnings,
      List<Method> afterThrowings,
      List<Method> afterFinallys,
      double sampleRate,
      boolean isTimed,
      Timed timedAnnotation) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        inv.setAttachment(ELAPSED_START_ATTACHMENT, System.nanoTime());
        if (isTimed) inv.setAttachment("timed.startNanos", System.nanoTime());
        invokeMethods(instance, arounds, inv);
        invokeMethods(instance, befores, inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        invokeMethods(instance, arounds, inv);
        invokeMethods(instance, afters, inv);
        invokeMethods(instance, afterReturnings, inv);
        if (isTimed) recordTiming(inv, timedAnnotation);
      }

      @Override
      public void onException(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        invokeMethods(instance, arounds, inv);
        invokeOnExceptionFiltered(instance, onExceptions, inv);
        invokeOnExceptionThrowingFiltered(instance, afterThrowings, inv);
        if (isTimed) recordTiming(inv, timedAnnotation);
      }

      @Override
      public void afterFinally(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        invokeMethods(instance, afterFinallys, inv);
      }

      private boolean shouldSample(double rate) {
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
      }
    };
  }

  private Interceptor createPatternInterceptor(
      Object instance, List<Method> methods, double sampleRate) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        invokeMethods(instance, methods, inv);
      }

      private boolean shouldSample(double rate) {
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
      }
    };
  }

  private Interceptor createConstructorInterceptor(
      Object instance, List<Method> methods, double sampleRate) {
    return new Interceptor() {
      @Override
      public void after(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        invokeMethods(instance, methods, inv);
      }

      private boolean shouldSample(double rate) {
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
      }
    };
  }

  private Interceptor createArgumentRewriteInterceptor(
      Object instance, List<Method> methods, double sampleRate) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        invokeMethods(instance, methods, inv);
      }

      private boolean shouldSample(double rate) {
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
      }
    };
  }

  private Interceptor createFieldGetInterceptor(Object instance, Method method, double sampleRate) {
    return new Interceptor() {
      @Override
      public void after(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        try {
          method.setAccessible(true);
          method.invoke(instance, inv);
        } catch (Exception e) {
          log.warn("Error invoking field-get method: {}", e.getMessage());
        }
      }

      private boolean shouldSample(double rate) {
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
      }
    };
  }

  private Interceptor createFieldSetInterceptor(Object instance, Method method, double sampleRate) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        try {
          method.setAccessible(true);
          method.invoke(instance, inv);
        } catch (Exception e) {
          log.warn("Error invoking field-set method: {}", e.getMessage());
        }
      }

      private boolean shouldSample(double rate) {
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
      }
    };
  }

  private Interceptor createStaticInitInterceptor(
      Object instance, List<Method> methods, double sampleRate) {
    return new Interceptor() {
      @Override
      public void after(MethodInvocation inv) {
        if (!shouldSample(sampleRate)) return;
        invokeMethods(instance, methods, inv);
      }

      private boolean shouldSample(double rate) {
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
      }
    };
  }

  /**
   * Registers a single {@code @OnCatch}-annotated method as a {@link CatchInterceptor}. Each
   * method becomes its own definition (own {@link CatchPointcut}) so that {@link
   * com.github.cc11001100.weavergirl.core.CatchAdvice} only invokes it once per matched catch
   * block, not once per sibling {@code @OnCatch} method in the same class.
   */
  private void registerCatchInterceptor(
      Object instance,
      Method method,
      ClassMatcher classMatcher,
      Class<?> clazz,
      int priority,
      InterceptorRegistry registry) {
    OnCatch ann = method.getAnnotation(OnCatch.class);
    Pointcut anyMethod = new Pointcut(classMatcher, MethodMatcher.any());
    CatchPointcut[] catchPointcuts = {new CatchPointcut(anyMethod, ann.value())};

    // InterceptorDefinition requires an Interceptor; CatchInterceptor is a separate,
    // narrower interface, so a small adapter class implements both.
    Interceptor catchInterceptor =
        new ReflectiveCatchInterceptor(instance, method, catchPointcuts, priority);

    String defName = "annotation-" + clazz.getSimpleName() + "-onCatch-" + method.getName();
    registry.register(
        new InterceptorDefinition(
            defName,
            anyMethod,
            catchInterceptor,
            priority,
            InterceptorDefinition.AdviceMode.CATCH,
            false,
            null,
            ann.value()));
  }

  /**
   * Adapts a single {@code @OnCatch}-annotated method into both {@link Interceptor} (required by
   * {@link InterceptorDefinition}) and {@link CatchInterceptor} (consulted by {@link
   * com.github.cc11001100.weavergirl.core.CatchAdvice}).
   */
  private static final class ReflectiveCatchInterceptor implements Interceptor, CatchInterceptor {
    private final Object instance;
    private final Method method;
    private final CatchPointcut[] catchPointcuts;
    private final int priority;

    ReflectiveCatchInterceptor(
        Object instance, Method method, CatchPointcut[] catchPointcuts, int priority) {
      this.instance = instance;
      this.method = method;
      this.catchPointcuts = catchPointcuts;
      this.priority = priority;
    }

    @Override
    public void onCatch(CatchInvocation invocation) {
      try {
        method.setAccessible(true);
        method.invoke(instance, invocation);
      } catch (Exception e) {
        log.warn("Error invoking @OnCatch method {}: {}", method.getName(), e.getMessage());
      }
    }

    @Override
    public CatchPointcut[] catchPointcuts() {
      return catchPointcuts;
    }

    @Override
    public int getPriority() {
      return priority;
    }
  }

  private Interceptor wrapWithRetry(
      Interceptor delegate, List<Method> retryMethods, Object instance) {
    RetryOnException retryAnn = retryMethods.get(0).getAnnotation(RetryOnException.class);
    int maxRetries = retryAnn.maxRetries();
    long delayMs = retryAnn.delayMs();
    Set<Class<? extends Throwable>> retryFor = new HashSet<>(Arrays.asList(retryAnn.retryFor()));
    return new Interceptor() {
      private final AtomicLong retryAttempt = new AtomicLong(0);

      @Override
      public void before(MethodInvocation inv) {
        retryAttempt.set(0);
        delegate.before(inv);
      }

      @Override
      public void after(MethodInvocation inv) {
        retryAttempt.set(0);
        delegate.after(inv);
      }

      @Override
      public void onException(MethodInvocation inv) {
        Throwable t = inv.getThrowable();
        if (t != null && shouldRetryFor(t, retryFor)) {
          long attempt = retryAttempt.incrementAndGet();
          if (attempt <= maxRetries) {
            log.info("Retrying {} (attempt {}/{})", inv.getMethodName(), attempt, maxRetries);
            if (delayMs > 0) {
              try {
                Thread.sleep(delayMs);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            inv.setAttachment("retry.attempt", attempt);
            invokeMethods(instance, retryMethods, inv);
            inv.suppressException();
            return;
          }
        }
        retryAttempt.set(0);
        delegate.onException(inv);
      }

      private boolean shouldRetryFor(Throwable t, Set<Class<? extends Throwable>> retryFor) {
        if (retryFor.isEmpty()) return true;
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
        m.invoke(inst, resolveArgs(m, inv));
      } catch (Exception e) {
        log.warn("Error invoking interceptor method {}: {}", m.getName(), e.getMessage());
      }
    }
  }

  private void invokeOnExceptionFiltered(
      Object inst, List<Method> onExceptions, MethodInvocation inv) {
    Throwable thrown = inv.getThrowable();
    for (Method m : onExceptions) {
      OnException ann = m.getAnnotation(OnException.class);
      if (ann != null && ann.exceptionType() != Throwable.class) {
        if (thrown != null && !ann.exceptionType().isInstance(thrown)) continue;
      }
      try {
        m.setAccessible(true);
        m.invoke(inst, resolveArgs(m, inv));
      } catch (Exception e) {
        log.warn("Error invoking onException method {}: {}", m.getName(), e.getMessage());
      }
    }
  }

  private void invokeOnExceptionThrowingFiltered(
      Object inst, List<Method> afterThrowings, MethodInvocation inv) {
    Throwable thrown = inv.getThrowable();
    for (Method m : afterThrowings) {
      AfterThrowing ann = m.getAnnotation(AfterThrowing.class);
      if (ann != null && ann.exceptionType() != Throwable.class) {
        if (thrown != null && !ann.exceptionType().isInstance(thrown)) continue;
      }
      try {
        m.setAccessible(true);
        m.invoke(inst, resolveArgs(m, inv));
      } catch (Exception e) {
        log.warn("Error invoking afterThrowing method {}: {}", m.getName(), e.getMessage());
      }
    }
  }

  /**
   * Builds the argument array for a reflectively-invoked advice method, honoring the {@code
   * @Arg}, {@code @Elapsed}, {@code @NotNull}, {@code @Origin}, {@code @Return}, and {@code @This}
   * parameter-injection annotations. A parameter with none of these annotations falls back to the
   * legacy behavior of receiving the {@link MethodInvocation} itself.
   */
  private Object[] resolveArgs(Method m, MethodInvocation inv) {
    Class<?>[] paramTypes = m.getParameterTypes();
    if (paramTypes.length == 0) {
      return new Object[0];
    }
    Annotation[][] paramAnnotations = m.getParameterAnnotations();
    Object[] args = new Object[paramTypes.length];
    for (int i = 0; i < paramTypes.length; i++) {
      args[i] = resolveParamValue(paramAnnotations[i], inv);
    }
    return args;
  }

  private Object resolveParamValue(Annotation[] annotations, MethodInvocation inv) {
    Arg argAnn = null;
    NotNull notNullAnn = null;
    boolean elapsed = false;
    boolean origin = false;
    boolean returnValue = false;
    boolean thisTarget = false;
    for (Annotation a : annotations) {
      if (a instanceof Arg) {
        argAnn = (Arg) a;
      } else if (a instanceof Elapsed) {
        elapsed = true;
      } else if (a instanceof NotNull) {
        notNullAnn = (NotNull) a;
      } else if (a instanceof Origin) {
        origin = true;
      } else if (a instanceof Return) {
        returnValue = true;
      } else if (a instanceof This) {
        thisTarget = true;
      }
    }

    Object value;
    if (argAnn != null) {
      value = inv.getArgument(argAnn.value());
    } else if (elapsed) {
      Object startNanos = inv.getAttachment(ELAPSED_START_ATTACHMENT);
      value = startNanos instanceof Long ? System.nanoTime() - (Long) startNanos : 0L;
    } else if (origin) {
      value = inv.getMethod();
    } else if (returnValue) {
      value = inv.getReturnValue();
    } else if (thisTarget) {
      value = inv.getTarget();
    } else {
      value = inv;
    }

    if (notNullAnn != null && value == null) {
      throw new IllegalArgumentException(notNullAnn.message());
    }
    return value;
  }

  private String resolvePointcutKey(String rawKey, Map<String, PointcutExpression> namedPointcuts) {
    if (rawKey == null || rawKey.isEmpty()) {
      return rawKey;
    }
    // Support named pointcut references: "pointcutName()"
    int parenIdx = rawKey.indexOf('(');
    int closeParenIdx = rawKey.indexOf(')');
    if (parenIdx > 0 && closeParenIdx == rawKey.length() - 1) {
      String inside = rawKey.substring(parenIdx + 1, closeParenIdx);
      // Only treat as named pointcut reference if parentheses are empty/whitespace-only.
      // This prevents method signatures like "save(int)" from being misparsed as pointcut refs.
      if (inside.trim().isEmpty()) {
        String name = rawKey.substring(0, parenIdx);
        PointcutExpression expr = namedPointcuts.get(name);
        if (expr != null) {
          return expr.toString();
        }
      }
    }
    return rawKey;
  }

  private String resolvePointcutKey(Before ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private String resolvePointcutKey(After ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private String resolvePointcutKey(AfterReturning ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private String resolvePointcutKey(AfterThrowing ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private String resolvePointcutKey(OnException ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private String resolvePointcutKey(Around ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private String resolvePointcutKey(AfterFinally ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private String resolvePointcutKey(RewriteArg ann, Map<String, PointcutExpression> namedPointcuts) {
    String base = resolvePointcutKey(ann.value(), namedPointcuts);
    if (ann.parameterTypes().length == 0) {
      return base;
    }
    return base + "(" + String.join(",", ann.parameterTypes()) + ")";
  }

  private void recordTiming(MethodInvocation invocation, Timed timedAnnotation) {
    Long startNanos = (Long) invocation.getAttachment("timed.startNanos");
    if (startNanos != null) {
      long elapsed = System.nanoTime() - startNanos;
      invocation.setAttachment("timed.elapsedNanos", elapsed);
      invocation.setAttachment("timed.elapsedMs", elapsed / 1_000_000);
      if (timedAnnotation != null && timedAnnotation.log()) {
        String label =
            timedAnnotation.label().isEmpty()
                ? invocation.getMethodName()
                : timedAnnotation.label();
        log.info("[TIMED] {} took {}ms", label, elapsed / 1_000_000);
      }
    }
  }

  private ClassMatcher buildClassMatcherFromAnnotation(WeaveClass weaveClass) {
    if (!weaveClass.targetAnnotation().isEmpty())
      return ClassMatcher.byAnnotation(weaveClass.targetAnnotation());
    if (!weaveClass.targetSuperClass().isEmpty())
      return ClassMatcher.bySuperClass(weaveClass.targetSuperClass());
    if (!weaveClass.targetInterface().isEmpty())
      return ClassMatcher.byInterface(weaveClass.targetInterface());
    if (!weaveClass.targetPattern().isEmpty())
      return ClassMatcher.byNamePattern(weaveClass.targetPattern());
    return ClassMatcher.byName(weaveClass.target());
  }

  private List<Method> flattenAll(Map<String, List<Method>> methodsMap) {
    List<Method> result = new ArrayList<>();
    for (List<Method> methods : methodsMap.values()) result.addAll(methods);
    return result;
  }

  // ==================== Inner state classes ====================

  private static class CircuitState {
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicInteger successes = new AtomicInteger(0);
    private volatile long openedAt = 0;
    private volatile boolean open = false;

    void recordFailure(int threshold) {
      if (failures.incrementAndGet() >= threshold) {
        open = true;
        openedAt = System.currentTimeMillis();
      }
    }

    void recordSuccess(int threshold) {
      successes.incrementAndGet();
      if (successes.get() >= threshold) {
        failures.set(0);
        successes.set(0);
        open = false;
      }
    }

    boolean isOpen(long timeoutMs) {
      if (!open) return false;
      if (System.currentTimeMillis() - openedAt > timeoutMs) {
        open = false; // half-open
        failures.set(0);
        successes.set(0);
        return false;
      }
      return true;
    }
  }

  private static class RateLimitState {
    private final double permitsPerSecond;
    private volatile double tokens;
    private volatile long lastRefillNanos;

    RateLimitState(double permitsPerSecond) {
      this.permitsPerSecond = permitsPerSecond;
      this.tokens = permitsPerSecond;
      this.lastRefillNanos = System.nanoTime();
    }

    synchronized boolean tryAcquire() {
      long now = System.nanoTime();
      double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
      tokens = Math.min(permitsPerSecond, tokens + elapsedSeconds * permitsPerSecond);
      lastRefillNanos = now;
      if (tokens >= 1.0) {
        tokens -= 1.0;
        return true;
      }
      return false;
    }
  }

  private static class CacheEntry {
    final Object value;
    final long expiryNanos;

    CacheEntry(Object value, long expiryNanos) {
      this.value = value;
      this.expiryNanos = expiryNanos;
    }

    boolean isExpired() {
      return expiryNanos > 0 && System.nanoTime() > expiryNanos;
    }
  }
}
