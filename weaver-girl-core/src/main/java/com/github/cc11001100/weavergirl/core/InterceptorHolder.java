package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.circuit.InterceptorCircuitBreaker;
import com.github.cc11001100.weavergirl.core.management.AgentMonitor;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global holder for the InterceptorRegistry instance. Needed because ByteBuddy Advice classes are
 * static and cannot access instance fields — they need a global reference.
 *
 * <p>Also provides static delegate methods for AgentStatus counters, so that InterceptAdvice (which
 * is inlined by ByteBuddy and cannot reference classes not on the bootstrap classloader) can
 * increment status counters indirectly.
 */
public class InterceptorHolder {

  private static final Logger LOG = LoggerFactory.getLogger(InterceptorHolder.class);

  private static volatile InterceptorRegistry registry;
  private static final InterceptorCircuitBreaker circuitBreaker = new InterceptorCircuitBreaker();

  // --- Dynamic dispatch cache ---
  // Building the class -> interceptor mapping per call used to cost O(definitions): every
  // non-exact-name definition was re-scanned — and, for ANNOTATION/SUPER_CLASS/INTERFACE, resolved
  // reflectively — on every intercepted method enter and exit, three times per call. With a
  // registry holding thousands of pointcuts that made each intercepted call cost thousands of
  // checks. This cache is rebuilt once per registry generation, then answered per class and per
  // method name, so an invocation pays one hash lookup on the class name and one on the method name
  // and iterates only the definitions that can actually apply. See InterceptAdvice.
  private static volatile DispatchCache dispatchCache;
  private static final Object dispatchCacheLock = new Object();
  private static final InterceptorDefinition[] NO_DEFINITIONS = new InterceptorDefinition[0];

  /**
   * The definitions selected for one intercepted method in one class, pre-computed for the inlined
   * advice. Immutable and shared by every invocation of that method until the registry changes.
   *
   * <p>Public (and with public fields) because the inlined {@code InterceptAdvice} code lives in the
   * application's package, not in this one: a package-private type or field would fail to resolve
   * from the woven class.
   */
  public static final class DispatchView {
    /** Matching definitions in dispatch order. Never null and never mutated. */
    public final InterceptorDefinition[] definitions;

    /** Whether any matching definition asked for call-site capture. */
    public final boolean tracksCallSite;

    /** Whether any matching definition implements around advice. */
    public final boolean anyAround;

    /** Whether any matching definition is instance-bound (pertarget/perthis). */
    public final boolean anyInstanceBound;

    /**
     * Whether at least one definition is dispatched by the standard enter/exit advice. When every
     * definition is {@code ARGUMENT_REWRITE} (handled by {@code AsyncArgumentAdvice}), the
     * standard advice has nothing to do and can bail out without a pooled invocation.
     */
    public final boolean hasStandardDefs;

    /** {@code className + "." + methodName}, cached so the exit path does not concatenate. */
    public final String methodKey;

    /**
     * The return-value version counter for {@link #methodKey}, resolved once. The exit path
     * increments it on every successful return; looking it up in a map each time was pure
     * overhead.
     */
    public final java.util.concurrent.atomic.AtomicLong versionCounter;

    DispatchView(
        InterceptorDefinition[] definitions,
        boolean tracksCallSite,
        boolean anyAround,
        boolean anyInstanceBound,
        boolean hasStandardDefs,
        String methodKey,
        java.util.concurrent.atomic.AtomicLong versionCounter) {
      this.definitions = definitions;
      this.tracksCallSite = tracksCallSite;
      this.anyAround = anyAround;
      this.anyInstanceBound = anyInstanceBound;
      this.hasStandardDefs = hasStandardDefs;
      this.methodKey = methodKey;
      this.versionCounter = versionCounter;
    }

    static final DispatchView EMPTY =
        new DispatchView(
            NO_DEFINITIONS,
            false,
            false,
            false,
            false,
            "",
            new java.util.concurrent.atomic.AtomicLong(0));
  }

  /** Per-class selection, built on first use in a registry generation. */
  private static final class ClassPlan {
    final String className;
    final InterceptorDefinition[] all;
    final java.util.List<InterceptorDefinition> allView;
    final boolean tracksCallSite;
    /** True when at least one definition can be filtered by the method name alone. */
    final boolean hasNameFilters;
    final java.util.concurrent.ConcurrentHashMap<String, DispatchView> views =
        new java.util.concurrent.ConcurrentHashMap<>();

    ClassPlan(
        DefaultInterceptorRegistry registry,
        String className,
        java.util.Map<String, java.util.List<InterceptorDefinition>> exactIndex,
        InterceptorDefinition[] nonExact,
        java.util.Map<String, Integer> precedence) {
      this.className = className;
      java.util.List<InterceptorDefinition> matched = new java.util.ArrayList<>();
      java.util.List<InterceptorDefinition> exactMatches = exactIndex.get(className);
      if (exactMatches != null) {
        matched.addAll(exactMatches);
      }
      for (int i = 0; i < nonExact.length; i++) {
        InterceptorDefinition def = nonExact[i];
        ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
        // Keep the two checks the registry has always used: the name-based one decides patterns
        // and ANY, the reflective one decides interface/superclass/annotation.
        if (classMatcher.matches(className)
            || registry.matchesByReflection(classMatcher, className)) {
          matched.add(def);
        }
      }
      // Same order as DefaultInterceptorRegistry#getInterceptorsForClass: priority, then
      // @DeclarePrecedence. The advice runs the interceptors in this order, so it must not differ
      // from what the registry would have answered.
      matched.sort(orderComparator(precedence));
      this.all = matched.toArray(NO_DEFINITIONS);
      this.allView = java.util.Collections.unmodifiableList(java.util.Arrays.asList(this.all));
      boolean callSite = false;
      boolean nameFilters = false;
      for (int i = 0; i < all.length; i++) {
        if (all[i].isCallSiteTracking()) {
          callSite = true;
        }
        if (isNameDecidable(all[i])) {
          nameFilters = true;
        }
      }
      this.tracksCallSite = callSite;
      this.hasNameFilters = nameFilters;
    }

    DispatchView dispatchFor(String methodName) {
      DispatchView cached = views.get(methodName);
      if (cached != null) {
        return cached;
      }
      DispatchView created = build(methodName);
      DispatchView prior = views.putIfAbsent(methodName, created);
      return prior != null ? prior : created;
    }

    private DispatchView build(String methodName) {
      InterceptorDefinition[] selected;
      if (!hasNameFilters) {
        // Every definition uses an ANY/annotation/args matcher: they all stay candidates for
        // every method, so the class-level selection is already the answer.
        selected = all;
      } else {
        java.util.List<InterceptorDefinition> kept = new java.util.ArrayList<>(all.length);
        for (int i = 0; i < all.length; i++) {
          if (canMatchMethod(all[i], methodName)) {
            kept.add(all[i]);
          }
        }
        selected = kept.isEmpty() ? NO_DEFINITIONS : kept.toArray(NO_DEFINITIONS);
      }
      String methodKey = className + "." + methodName;
      return new DispatchView(
          selected,
          tracksCallSite,
          anyAround(selected),
          anyInstanceBound(selected),
          hasStandardDefs(selected),
          methodKey,
          com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion.counterFor(methodKey));
    }
  }

  /** One registry generation's definitions, split into exact-name and scanned halves. */
  private static final class DispatchCache {
    final DefaultInterceptorRegistry registry;
    final long generation;
    final java.util.Map<String, java.util.List<InterceptorDefinition>> exactByClass;
    final InterceptorDefinition[] nonExact;
    final java.util.Map<String, Integer> precedence;
    final java.util.concurrent.ConcurrentHashMap<String, ClassPlan> plans =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentHashMap<Class<?>, InterceptorDefinition[]> byInterceptorType =
        new java.util.concurrent.ConcurrentHashMap<>();

    DispatchCache(DefaultInterceptorRegistry registry, long generation) {
      this.registry = registry;
      this.generation = generation;
      java.util.Map<String, java.util.List<InterceptorDefinition>> exact = new java.util.HashMap<>();
      java.util.List<InterceptorDefinition> other = new java.util.ArrayList<>();
      java.util.List<InterceptorDefinition> definitions = registry.getAllDefinitions();
      for (int i = 0; i < definitions.size(); i++) {
        InterceptorDefinition def = definitions.get(i);
        ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
        if (classMatcher.getMatchType() == ClassMatcher.MatchType.EXACT_NAME) {
          exact
              .computeIfAbsent(classMatcher.getPattern(), k -> new java.util.ArrayList<>())
              .add(def);
        } else {
          other.add(def);
        }
      }
      for (java.util.List<InterceptorDefinition> list : exact.values()) {
        list.sort(java.util.Comparator.comparingInt(InterceptorDefinition::getPriority));
      }
      this.exactByClass = exact;
      this.nonExact = other.toArray(NO_DEFINITIONS);
      // Resolved once per generation, not per class: it triggers a lazy precedence rebuild at
      // most once, and only when some aspect actually declares one.
      this.precedence = registry.getPrecedenceMap();
    }

    ClassPlan plan(String className) {
      ClassPlan plan = plans.get(className);
      if (plan != null) {
        return plan;
      }
      ClassPlan created = new ClassPlan(registry, className, exactByClass, nonExact, precedence);
      ClassPlan prior = plans.putIfAbsent(className, created);
      return prior != null ? prior : created;
    }

    /**
     * The definitions whose interceptor is an instance of {@code type}, in registration order.
     *
     * <p>Field and catch advice filter the whole registry by interceptor type on every event;
     * with thousands of pointcuts that is a per-access scan of everything. The type of a
     * registered interceptor cannot change, so the selection is stable for a generation.
     */
    InterceptorDefinition[] withInterceptorType(Class<?> type) {
      InterceptorDefinition[] cached = byInterceptorType.get(type);
      if (cached != null) {
        return cached;
      }
      java.util.List<InterceptorDefinition> allDefs = registry.getAllDefinitions();
      java.util.List<InterceptorDefinition> kept = new java.util.ArrayList<>();
      for (int i = 0; i < allDefs.size(); i++) {
        InterceptorDefinition def = allDefs.get(i);
        if (type.isInstance(def.getInterceptor())) {
          kept.add(def);
        }
      }
      InterceptorDefinition[] created =
          kept.isEmpty() ? NO_DEFINITIONS : kept.toArray(NO_DEFINITIONS);
      InterceptorDefinition[] prior = byInterceptorType.putIfAbsent(type, created);
      return prior != null ? prior : created;
    }
  }

  /** Priority order, refined by {@code @DeclarePrecedence} when any aspect declares one. */
  private static java.util.Comparator<InterceptorDefinition> orderComparator(
      java.util.Map<String, Integer> precedence) {
    if (precedence == null || precedence.isEmpty()) {
      return java.util.Comparator.comparingInt(InterceptorDefinition::getPriority);
    }
    return (a, b) -> {
      Integer pA = precedence.get(a.getAspectClassName());
      Integer pB = precedence.get(b.getAspectClassName());
      int cmp =
          Integer.compare(
              pA != null ? pA : Integer.MAX_VALUE, pB != null ? pB : Integer.MAX_VALUE);
      if (cmp != 0) {
        return cmp;
      }
      return Integer.compare(a.getPriority(), b.getPriority());
    };
  }

  /**
   * Whether a definition's method matcher can be decided from the method name alone.
   *
   * <p>Only a plain {@link Pointcut} qualifies. A composed pointcut ({@code and/or/not}) exposes a
   * combined method matcher that is merely an approximation of the whole expression — for example
   * the OR of two exact names reports only the first — so filtering by it would silently drop join
   * points the expression really matches.
   *
   * <p>ANY, ANNOTATION and ARGS matchers depend on more than the name (or must stay candidates
   * because the annotation decision was already made at weave time), so those are never filtered.
   */
  private static boolean isNameDecidable(InterceptorDefinition def) {
    Pointcut pointcut = def.getPointcut();
    if (pointcut.getClass() != Pointcut.class) {
      return false;
    }
    switch (pointcut.getMethodMatcher().getMatchType()) {
      case EXACT_NAME:
      case NAME_PATTERN:
      case SIGNATURE:
      case CONSTRUCTOR:
      case CFIELD_GET:
      case CFIELD_SET:
        return true;
      default:
        return false;
    }
  }

  /**
   * Whether {@code def} can still match {@code methodName}.
   *
   * <p>For name-decidable matchers this is the same check {@code matchRuntime} would run, so
   * dropping the definition here changes nothing but the work done per invocation. Everything else
   * is kept and left to {@code matchRuntime}.
   */
  private static boolean canMatchMethod(InterceptorDefinition def, String methodName) {
    if (!isNameDecidable(def)) {
      return true;
    }
    return def.getPointcut().getMethodMatcher().matches(methodName);
  }

  private static boolean anyAround(InterceptorDefinition[] definitions) {
    for (int i = 0; i < definitions.length; i++) {
      if (definitions[i].getInterceptor().hasAround()) {
        return true;
      }
    }
    return false;
  }

  private static boolean anyInstanceBound(InterceptorDefinition[] definitions) {
    for (int i = 0; i < definitions.length; i++) {
      if (definitions[i].getPointcut().isInstanceBound()) {
        return true;
      }
    }
    return false;
  }

  /** Whether any definition is dispatched by the standard enter/exit advice. */
  private static boolean hasStandardDefs(InterceptorDefinition[] definitions) {
    for (int i = 0; i < definitions.length; i++) {
      if (definitions[i].getAdviceMode() != InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE) {
        return true;
      }
    }
    return false;
  }

  public static void logInterceptorError(String interceptorName, String phase, Throwable e) {
    LOG.warn("Interceptor '{}' failed in {}: {}", interceptorName, phase, e.getMessage());
  }

  public static void setRegistry(InterceptorRegistry registry) {
    dispatchCache = null; // invalidate cache on registry swap
    InterceptorHolder.registry = registry;
  }

  /**
   * Callback run after a config reload so already-loaded classes pick up the new interceptors.
   *
   * <p>This lives here, and not on {@code ConfigWatcher}, because the watcher is loaded inside the
   * agent's isolated ClassLoader while the code that installs the callback may be loaded outside it.
   * This class is resolved parent-first, so both sides share the one field.
   */
  private static volatile Runnable retransformHook;

  /** Install the callback a config reload runs to retransform loaded classes. {@code null} clears it. */
  public static void setRetransformHook(Runnable hook) {
    retransformHook = hook;
  }

  /** The installed retransform callback, or {@code null} when none is installed. */
  public static Runnable getRetransformHook() {
    return retransformHook;
  }

  public static InterceptorRegistry getRegistry() {
    return registry;
  }

  /** The cache for {@code registry} at its current generation, rebuilding it when necessary. */
  private static DispatchCache cacheFor(DefaultInterceptorRegistry registry) {
    long generation = registry.getGeneration();
    DispatchCache cache = dispatchCache;
    if (cache != null && cache.registry == registry && cache.generation == generation) {
      return cache;
    }
    synchronized (dispatchCacheLock) {
      cache = dispatchCache;
      if (cache == null || cache.registry != registry || cache.generation != generation) {
        cache = new DispatchCache(registry, generation);
        dispatchCache = cache;
      }
      return cache;
    }
  }

  /**
   * The interceptors matching {@code className}, priority-ascending.
   *
   * <p>Served from the per-generation dispatch cache: a registry holding thousands of pointcuts is
   * classified once, not scanned per call. The returned list is immutable and shared — callers must
   * not mutate it.
   */
  public static java.util.List<InterceptorDefinition> getInterceptorsForClassSnapshot(String className) {
    InterceptorRegistry reg = registry;
    if (reg == null) {
      return java.util.Collections.emptyList();
    }
    if (!(reg instanceof DefaultInterceptorRegistry)) {
      return reg.getInterceptorsForClass(className);
    }
    return cacheFor((DefaultInterceptorRegistry) reg).plan(className).allView;
  }

  /**
   * The definitions that can apply to one method of one class, pre-computed for the inlined advice.
   *
   * <p>This is the hot-path entry point: the result is cached per registry generation, class and
   * method name, so repeated invocations of the same method cost two hash lookups regardless of how
   * many pointcuts are registered. Definitions whose method matcher is an exact name are filtered
   * out for every other method name; pattern/annotation/ANY matchers stay candidates and are still
   * checked per invocation with {@code matchRuntime}.
   *
   * @param className the intercepted class
   * @param methodName the intercepted method name ({@code "<init>"} for constructors)
   * @return the dispatch view, never null
   */
  public static DispatchView dispatch(String className, String methodName) {
    InterceptorRegistry reg = registry;
    if (reg == null || className == null) {
      return DispatchView.EMPTY;
    }
    if (!(reg instanceof DefaultInterceptorRegistry)) {
      java.util.List<InterceptorDefinition> defs = reg.getInterceptorsForClass(className);
      InterceptorDefinition[] array =
          defs.isEmpty() ? NO_DEFINITIONS : defs.toArray(NO_DEFINITIONS);
      boolean callSite = false;
      for (int i = 0; i < array.length; i++) {
        if (array[i].isCallSiteTracking()) {
          callSite = true;
          break;
        }
      }
      String methodKey = className + "." + methodName;
      return new DispatchView(
          array,
          callSite,
          anyAround(array),
          anyInstanceBound(array),
          hasStandardDefs(array),
          methodKey,
          com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion.counterFor(methodKey));
    }
    return cacheFor((DefaultInterceptorRegistry) reg).plan(className).dispatchFor(methodName);
  }

  /**
   * The definitions whose interceptor is an instance of {@code type}, in registration order.
   *
   * <p>Served from the per-generation cache, so field and catch advice do not scan the whole
   * registry on every event when thousands of pointcuts are registered.
   *
   * @param type the interceptor type to filter by (e.g. {@code FieldInterceptor})
   * @return a shared, immutable array; never null
   */
  public static InterceptorDefinition[] definitionsWithInterceptorType(Class<?> type) {
    InterceptorRegistry reg = registry;
    if (reg == null || type == null) {
      return NO_DEFINITIONS;
    }
    if (!(reg instanceof DefaultInterceptorRegistry)) {
      java.util.List<InterceptorDefinition> allDefs = reg.getAllDefinitions();
      java.util.List<InterceptorDefinition> kept = new java.util.ArrayList<>();
      for (int i = 0; i < allDefs.size(); i++) {
        if (type.isInstance(allDefs.get(i).getInterceptor())) {
          kept.add(allDefs.get(i));
        }
      }
      return kept.isEmpty() ? NO_DEFINITIONS : kept.toArray(NO_DEFINITIONS);
    }
    return cacheFor((DefaultInterceptorRegistry) reg).withInterceptorType(type);
  }

  /**
   * Force-invalidate the cached dispatch views so the next lookup rebuilds from the underlying
   * registry. Called by {@code DefaultInterceptorRegistry}'s reload hooks and on registry swap.
   */
  public static void invalidateSnapshot() {
    dispatchCache = null;
  }

  // --- Global interception switch delegates ---

  /**
   * @return true if global interception is enabled. Consulted by {@code InterceptAdvice} on every
   *     enter/exit as a single volatile read.
   */
  public static boolean isInterceptionEnabled() {
    return GlobalInterceptionSwitch.isEnabled();
  }

  /** Process-wide kill-switch toggle. */
  public static void setInterceptionEnabled(boolean enabledFlag, String source) {
    GlobalInterceptionSwitch.setEnabled(enabledFlag, source);
  }

  // --- AgentStatus delegates ---

  public static void incrementInterceptorInvocationCount() {
    AgentStatus.getInstance().incrementInterceptorInvocationCount();
    AgentMonitor.getInstance().incrementInterceptCount();
  }

  /**
   * The current registry generation, or {@code 0} when no registry is installed.
   *
   * <p>The inlined advice stamps the generation onto the state it carries from enter to exit; exit
   * reuses enter's dispatch view only when this value is unchanged, i.e. when no registration
   * happened during the call. One volatile read instead of a hash lookup on the hot path.
   *
   * @return the generation of the installed registry, or 0
   */
  public static long currentGeneration() {
    InterceptorRegistry reg = registry;
    if (reg instanceof DefaultInterceptorRegistry) {
      return ((DefaultInterceptorRegistry) reg).getGeneration();
    }
    return 0L;
  }

  public static void incrementInterceptorErrorCount() {
    AgentStatus.getInstance().incrementInterceptorErrorCount();
  }

  public static void recordInterceptTime(long nanos) {
    AgentMonitor.getInstance().addInterceptTime(nanos);
  }

  // --- Circuit breaker delegates ---

  /**
   * Whether a hook may run now. When the breaker is switched off
   * ({@code -Dweavergirl.circuitBreaker.enabled=false}), every hook is allowed — the breaker
   * reasons about hook health, which a deployment that never wants auto-degradation does not need.
   */
  public static boolean shouldInvoke(String interceptorName) {
    if (!com.github.cc11001100.weavergirl.api.config.PerformanceSwitches
        .isCircuitBreakerEnabled()) {
      return true;
    }
    return circuitBreaker.shouldInvoke(interceptorName);
  }

  /**
   * Retune the per-hook circuit breaker from agent configuration.
   *
   * <p>The breaker is a static singleton because the inlined advice reaches it parent-first; this
   * is the one seam that lets bootstrap and dynamic config change its thresholds.
   *
   * @param failureThreshold consecutive failures that trip a hook, {@code <= 0} keeps the current
   * @param cooldownMillis cooldown before a retry, {@code <= 0} keeps the current
   */
  public static void configureCircuitBreaker(int failureThreshold, long cooldownMillis) {
    circuitBreaker.configure(failureThreshold, cooldownMillis);
  }

  /** Clear every hook's breaker state and restore the defaults. For test cleanup only. */
  public static void resetCircuitBreakerForTest() {
    circuitBreaker.resetForTest();
  }

  /** @return the breaker's current failure threshold, for diagnostics and tests. */
  public static int getCircuitBreakerFailureThreshold() {
    return circuitBreaker.getFailureThreshold();
  }

  /** @return the breaker's current cooldown in millis, for diagnostics and tests. */
  public static long getCircuitBreakerCooldownMillis() {
    return circuitBreaker.getCooldownMillis();
  }

  public static void recordInterceptorSuccess(String interceptorName) {
    circuitBreaker.recordSuccess(interceptorName);
  }

  public static void recordInterceptorFailure(String interceptorName) {
    circuitBreaker.recordFailure(interceptorName);
  }

  /**
   * Record an interceptor outcome and its duration, driving both failure-count and slow-call
   * (auto-degradation) circuit breaking in one call. Used by the inlined {@code @Advice} so a hook
   * that is consistently slow — but never throws — is still tripped.
   *
   * Also records plugin-level performance metrics for health monitoring.
   */
  public static void recordOutcome(String interceptorName, boolean success, long durationNanos) {
    if (com.github.cc11001100.weavergirl.api.config.PerformanceSwitches
        .isCircuitBreakerEnabled()) {
      circuitBreaker.recordOutcome(interceptorName, success, durationNanos);
    }
    // Record plugin-level metrics for health monitoring. The name is parsed once per hook; the
    // parse allocates substrings and this runs on every intercepted call. Switchable separately:
    // the plugin mirror duplicates what the per-hook metrics already record.
    if (!com.github.cc11001100.weavergirl.api.config.PerformanceSwitches
        .isPluginMetricsEnabled()) {
      return;
    }
    try {
      String pluginName = pluginNameFor(interceptorName);
      if (pluginName != null) {
        com.github.cc11001100.weavergirl.api.plugin.PluginPerformanceTracker.recordInvocationByName(
            pluginName, durationNanos / 1_000_000, !success);
      }
    } catch (Throwable t) {
      // Don't let plugin performance tracking break the core interceptor pipeline
      LOG.debug("Failed to record plugin performance metrics: {}", t.getMessage());
    }
  }

  /** Parsed plugin names, with {@code ""} standing in for "no plugin" (null is not cacheable). */
  private static final java.util.concurrent.ConcurrentHashMap<String, String> PLUGIN_NAMES =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static String pluginNameFor(String interceptorName) {
    if (interceptorName == null) {
      return null;
    }
    String cached = PLUGIN_NAMES.get(interceptorName);
    if (cached != null) {
      return cached.isEmpty() ? null : cached;
    }
    String parsed = extractPluginName(interceptorName);
    String stored = parsed == null ? "" : parsed;
    String prior = PLUGIN_NAMES.putIfAbsent(interceptorName, stored);
    return prior != null ? (prior.isEmpty() ? null : prior) : parsed;
  }

  /**
   * Extract plugin name from interceptor definition name.
   *
   * <p>Definition naming convention: {@code pluginName-classPattern-methodPattern} or
   * {@code pluginName-field-fieldName} or {@code pluginName-catch-exceptionType}.
   * Extract everything before the first dot (which separates class pattern from method pattern).
   */
  private static String extractPluginName(String interceptorName) {
    if (interceptorName == null) {
      return null;
    }
    int dotIndex = interceptorName.indexOf('.');
    if (dotIndex < 0) {
      // No class pattern (unusual), return the whole name
      return interceptorName;
    }
    // Find the last hyphen before the dot to separate plugin name from class pattern
    int lastHyphen = interceptorName.lastIndexOf('-', dotIndex);
    if (lastHyphen < 0) {
      // No hyphen before dot, entire name is plugin name
      return interceptorName.substring(0, dotIndex);
    }
    // Skip kind markers like "-field-" or "-catch-"
    String beforeHyphen = interceptorName.substring(0, lastHyphen);
    int kindMarker = beforeHyphen.lastIndexOf('-');
    if (kindMarker >= 0) {
      String suffix = beforeHyphen.substring(kindMarker + 1);
      if ("field".equals(suffix) || "catch".equals(suffix)) {
        return beforeHyphen.substring(0, kindMarker);
      }
    }
    return beforeHyphen;
  }

  /**
   * Read-only snapshot of every interceptor's circuit-breaker state, for the agent's /stats
   * exposition. Returns an empty list until any hook has been observed.
   */
  public static java.util.List<InterceptorCircuitBreaker.BreakerSnapshot> getBreakerSnapshots() {
    return circuitBreaker.snapshot();
  }

  // --- CallSite tracking delegate ---

  /**
   * Whether any definition matching {@code className} asked for call-site tracking.
   *
   * <p>Answered from the same per-generation class plan the dispatch loop uses, so it costs one
   * hash lookup and no registry scan. The inlined advice reads {@code DispatchView.tracksCallSite}
   * instead (it already holds the view); this method remains for callers that only have the class
   * name.
   *
   * @param className the intercepted class
   * @return true when at least one matching definition opted into call-site tracking
   */
  public static boolean tracksCallSite(String className) {
    InterceptorRegistry reg = registry;
    if (reg == null || className == null) {
      return false;
    }
    if (!(reg instanceof DefaultInterceptorRegistry)) {
      java.util.List<InterceptorDefinition> defs = reg.getInterceptorsForClass(className);
      for (int i = 0; i < defs.size(); i++) {
        if (defs.get(i).isCallSiteTracking()) {
          return true;
        }
      }
      return false;
    }
    return cacheFor((DefaultInterceptorRegistry) reg).plan(className).tracksCallSite;
  }

  /**
   * Capture the caller information for the intercepted method invocation. Called by the inlined
   * advice at method/constructor entry time.
   *
   * <p>This method lives in InterceptorHolder (not in the advice class itself) because ByteBuddy
   * validates the entire advice class when inlining — any method referencing {@code
   * Throwable.getStackTrace()} or {@code Thread.getStackTrace()} in the advice class causes the
   * advice to fail silently. By delegating through InterceptorHolder, the inlined code only
   * contains a static method call, and the actual stack-walking logic stays on the agent
   * classloader.
   *
   * @param invocation the MethodInvocation to populate with caller info
   */
  public static void captureCaller(MethodInvocation invocation) {
    StackTraceElement[] stack = new Throwable().getStackTrace();
    // stack[0] = getStackTrace
    // stack[1] = captureCaller (InterceptorHolder)
    // stack[2] = onMethodEnter (inlined into target method by ByteBuddy)
    // stack[3+] = the actual caller frames
    //
    // We scan from index 3 onward for the first frame that doesn't
    // belong to the target class AND doesn't belong to the agent
    // infrastructure (InterceptorHolder, InterceptAdvice, etc.).
    String targetClassName =
        invocation.getTargetClass() != null ? invocation.getTargetClass().getName() : null;
    int callerIndex = -1;
    for (int i = 3; i < stack.length; i++) {
      String frameClassName = stack[i].getClassName();
      // Skip frames that belong to the intercepted target class
      // (the inlined advice may appear as multiple frames)
      if (frameClassName.equals(targetClassName)) {
        continue;
      }
      // Skip agent infrastructure frames
      if (frameClassName.startsWith("com.github.cc11001100.weavergirl.core.")
          || frameClassName.startsWith("com.github.cc11001100.weavergirl.api.")) {
        continue;
      }
      // Skip JDK reflection / method-handle frames that sit between
      // the real caller and the intercepted method
      if (frameClassName.startsWith("jdk.internal.reflect.")
          || frameClassName.startsWith("sun.reflect.")
          || frameClassName.startsWith("java.lang.reflect.")) {
        continue;
      }
      // Skip test / framework intermediaries (JUnit, TestNG, etc.)
      if (frameClassName.startsWith("org.junit.")
          || frameClassName.startsWith("org.testng.")
          || frameClassName.startsWith("org.junit.platform.commons.")) {
        continue;
      }
      // Skip JDK utility frames that may appear in test runners
      if (frameClassName.startsWith("java.util.") || frameClassName.startsWith("java.lang.")) {
        continue;
      }
      // First non-target, non-agent, non-JDK, non-test frame is the real caller
      callerIndex = i;
      break;
    }
    if (callerIndex >= 0) {
      StackTraceElement callerFrame = stack[callerIndex];
      String callerClassName = callerFrame.getClassName();
      try {
        invocation.setCaller(
            Class.forName(callerClassName, false, Thread.currentThread().getContextClassLoader()),
            callerFrame.getMethodName(),
            callerFrame.getLineNumber());
      } catch (ClassNotFoundException e) {
        // Caller class not visible to our classloader — store what we can
        invocation.setCaller(null, callerFrame.getMethodName(), callerFrame.getLineNumber());
      }
    }
  }
}
