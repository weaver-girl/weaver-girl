package com.github.cc11001100.weavergirl.core.transformer;

import static net.bytebuddy.matcher.ElementMatchers.hasParameters;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.whereAny;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.AsyncArgumentAdvice;
import com.github.cc11001100.weavergirl.core.CatchAdvice;
import com.github.cc11001100.weavergirl.core.ConstructorAdvice;
import com.github.cc11001100.weavergirl.core.FieldAdvice;
import com.github.cc11001100.weavergirl.core.InterceptAdvice;
import com.github.cc11001100.weavergirl.core.StaticInitAdvice;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.management.AgentDiagnostics;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.ParameterDescription;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core transformer that registers ByteBuddy type transformers based on registered interceptor
 * definitions.
 */
public class WeaverTransformer {

  /** Test hook: transform of this class name throws instead of rewriting bytecode. */
  private static final ConcurrentHashMap<String, Throwable> FORCED_FAILURES =
      new ConcurrentHashMap<String, Throwable>();

  static void failTransform(String className, Throwable failure) {
    if (className != null && failure != null) {
      FORCED_FAILURES.put(className, failure);
    }
  }

  static void clearForcedFailures() {
    FORCED_FAILURES.clear();
  }

  /**
   * Prefixes are concatenated so the shade relocation of {@code net.bytebuddy} cannot rewrite the
   * host-class check into the agent's shaded package.
   */
  static String byteBuddyPrefix() {
    // Not a compile-time constant, so shade cannot rewrite the host package name.
    return new StringBuilder("net.").append("bytebuddy.").toString();
  }

  static String shadedByteBuddyPrefix() {
    return new StringBuilder("shaded.").append(byteBuddyPrefix()).toString();
  }

  static String asmPrefix() {
    return new StringBuilder("org.").append("objectweb.").append("asm.").toString();
  }

  /**
   * Byte Buddy, ASM, the agent entry, and the shade prefix are never rewritten. Classes under
   * {@code weavergirl.core} stay eligible when {@link #setIgnoreAgentClasses(boolean)} is false,
   * because tests and application types live in that tree.
   */
  static boolean isNeverRewritten(String className) {
    if (className == null) {
      return false;
    }
    return className.startsWith(byteBuddyPrefix())
        || className.startsWith(shadedByteBuddyPrefix())
        || className.startsWith(asmPrefix())
        || className.startsWith("com.github.cc11001100.weavergirl.shade.")
        || className.startsWith("com.github.cc11001100.weavergirl.agent.");
  }

  /** Matcher installed by {@link #install(Instrumentation, boolean)}. */
  static net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> ignoredTypes(
      boolean ignoreAgentClasses, List<String> excludedClassPatterns) {
    net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> excludeMatcher =
        nameStartsWith("sun.")
            .or(nameStartsWith("jdk.internal."))
            .or(nameStartsWith("com.sun."))
            .or(
                new net.bytebuddy.matcher.ElementMatcher.Junction.AbstractBase<TypeDescription>() {
                  @Override
                  public boolean matches(TypeDescription target) {
                    return isNeverRewritten(target == null ? null : target.getName());
                  }
                });
    if (ignoreAgentClasses) {
      excludeMatcher =
          excludeMatcher
              .or(nameStartsWith("com.github.cc11001100.weavergirl."))
              .or(nameStartsWith("com.github.cc11001100.weavergirl.shade."));
    }
    if (excludedClassPatterns != null) {
      for (String pattern : excludedClassPatterns) {
        excludeMatcher = excludeMatcher.or(nameMatches(pattern));
      }
    }
    return excludeMatcher;
  }

  private static final Logger log = LoggerFactory.getLogger(WeaverTransformer.class);

  private final InterceptorRegistry registry;
  private com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry introductionRegistry;
  private Instrumentation instrumentation;
  private List<String> excludedClassPatterns = Collections.emptyList();
  private boolean ignoreAgentClasses = true;
  private WeaverConfig weaverConfig;
  private final AtomicInteger transformationCount = new AtomicInteger(0);
  private int maxTransformations = 10000; // default
  /**
   * The {@link net.bytebuddy.agent.builder.ResettableClassFileTransformer} returned by {@code
   * installOn}. Holds the installed transformer chain so {@link #unweave()} can reset it and
   * restore already-woven classes to their original bytecode.
   */
  private volatile net.bytebuddy.agent.builder.ResettableClassFileTransformer resettable;
  /**
   * Class names this transformer actually wove, recorded by the transformation listener. Scoped to
   * this transformer (unlike the global {@code AgentStatus} transformed list) so {@link #unweave()}
   * can report exactly how many classes the reset restores.
   */
  private final java.util.Set<String> wovenClasses =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  public WeaverTransformer(InterceptorRegistry registry) {
    this.registry = registry;
  }

  public void setExcludedClassPatterns(List<String> patterns) {
    this.excludedClassPatterns = patterns != null ? patterns : Collections.emptyList();
  }

  /**
   * Set whether to ignore the agent's own classes, shaded dependencies, and JDK internals. Defaults
   * to true; set to false in test environments where test target classes live inside the weavergirl
   * package tree.
   */
  public void setIgnoreAgentClasses(boolean ignoreAgentClasses) {
    this.ignoreAgentClasses = ignoreAgentClasses;
  }

  /** Set the WeaverConfig for scope control (onlyInterceptPackages, maxTransformations). */
  public void setWeaverConfig(WeaverConfig weaverConfig) {
    this.weaverConfig = weaverConfig;
    if (weaverConfig != null) {
      if (weaverConfig.getMaxTransformations() != null) {
        this.maxTransformations = weaverConfig.getMaxTransformations();
      }
    }
  }

  /** Set the maximum number of classes that may be transformed. */
  public void setMaxTransformations(int max) {
    this.maxTransformations = max;
  }

  private boolean isDebugMode() {
    return Boolean.getBoolean("weavergirl.debug");
  }

  /**
   * Install this transformer onto the given Instrumentation instance. Equivalent to {@code
   * install(instrumentation, true)}: already-loaded classes matching any interceptor are
   * retransformed during install.
   */
  public void install(Instrumentation instrumentation) {
    install(instrumentation, true);
  }

  /**
   * Install this transformer onto the given Instrumentation instance.
   *
   * @param eagerRetransform if true, request ByteBuddy to retransform all already-loaded classes
   *     matching any interceptor during install (the {@link
   *     AgentBuilder.RedefinitionStrategy#RETRANSFORMATION} scan). This is required when a target
   *     class may already be loaded before install — e.g. a unit test instrumenting a class it has
   *     already referenced. Pass false in the production agent: at premain nothing the plugins
   *     target is loaded yet (the scan is pure waste against ~thousands of JDK classes), and at
   *     agentmain already-loaded classes are retransformed explicitly via {@link
   *     #retransformLoadedClasses()}. Skipping the eager scan is the dominant startup-time win (the
   *     scan dominated bootstrap).
   */
  public void install(Instrumentation instrumentation, boolean eagerRetransform) {
    this.instrumentation = instrumentation;

    // NOTE: the previous custom BootstrapInjection.append(agentJar) was removed.
    // Appending the whole agent JAR (which bundles ByteBuddy) to the bootstrap
    // classloader while the same JAR is also on the app classpath (via -javaagent)
    // produced a classloader split — net.bytebuddy classes resolved to different
    // Class objects in the app vs bootstrap loaders, raising a loader-constraint
    // violation on every real -javaagent attach. Bootstrap injection of the
    // advice helper classes is instead handled by ByteBuddy's own
    // AgentBuilder.InjectionStrategy.UsingInstrumentation configured below, which
    // extracts ONLY the needed helper classes into a temp JAR (no ByteBuddy on the
    // bootstrap path), avoiding the split.

    // Build the ignore matcher: exclude JDK internals that must never be
    // instrumented (risk of destabilizing the JVM + ClassCircularityError).
    // NOTE: java.*/javax.* are intentionally NOT excluded here — built-in
    // plugins match via bySuperClass/byInterface, whose hasSuperType matcher
    // DOES transform the base javax/java types themselves (e.g.
    // javax.servlet.http.HttpServlet, java.sql.Statement), and servlet/jdbc
    // interception depends on that. Excluding them silently breaks interception.
    net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> excludeMatcher =
        ignoredTypes(ignoreAgentClasses, excludedClassPatterns);

    AgentBuilder agentBuilder =
        new AgentBuilder.Default()
            .ignore(excludeMatcher)
            .disableClassFormatChanges()
            .with(
                new AgentBuilder.InjectionStrategy.UsingInstrumentation(
                    instrumentation, new java.io.File(System.getProperty("java.io.tmpdir"))))
            .with(
                new AgentBuilder.Listener.Adapter() {
                  @Override
                  public void onTransformation(
                      TypeDescription typeDescription,
                      ClassLoader classLoader,
                      JavaModule module,
                      boolean loaded,
                      DynamicType dynamicType) {
                    AgentStatus.getInstance().incrementTransformationCount();
                    AgentStatus.getInstance().addTransformedClass(typeDescription.getName());
                    wovenClasses.add(typeDescription.getName());
                    log.info("Transformed class: {}", typeDescription.getName());
                    if (isDebugMode()) {
                      List<String> matchedNames =
                          registry.getInterceptorsForClass(typeDescription.getName()).stream()
                              .map(InterceptorDefinition::getName)
                              .collect(Collectors.toList());
                      log.debug(
                          "  Interceptors matching {}: {}",
                          typeDescription.getName(),
                          matchedNames);
                    }
                  }

                  @Override
                  public void onIgnored(
                      TypeDescription typeDescription,
                      ClassLoader classLoader,
                      JavaModule module,
                      boolean loaded) {
                    if (isDebugMode()) {
                      log.debug(
                          "Ignored class (no matching interceptor): {}", typeDescription.getName());
                    }
                  }

                  @Override
                  public void onError(
                      String typeName,
                      ClassLoader classLoader,
                      JavaModule module,
                      boolean loaded,
                      Throwable throwable) {
                    noteTransformFailure(typeName, throwable);
                  }
                });

    // The transformer must be retransform-capable so agentmain can revisit classes loaded
    // before attach. The eager scan of every loaded class stays opt-in; otherwise discovery
    // is empty and retransformLoadedClasses() drives the batch one class at a time.
    AgentBuilder.RedefinitionListenable.WithImplicitDiscoveryStrategy redefinable =
        agentBuilder
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(1));
    if (eagerRetransform) {
      agentBuilder = redefinable;
    } else {
      agentBuilder =
          redefinable.with(
              new AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(
                  java.util.Collections.<Class<?>>emptySet()));
    }

    // If onlyInterceptPackages is specified, only match classes in those packages
    List<String> allowedPackages =
        weaverConfig != null ? weaverConfig.getOnlyInterceptPackages() : null;
    net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> packageAllowMatcher = null;
    if (allowedPackages != null && !allowedPackages.isEmpty()) {
      for (String pkg : allowedPackages) {
        String prefix = pkg.endsWith(".") ? pkg : pkg + ".";
        net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> pkgMatcher =
            nameStartsWith(prefix);
        packageAllowMatcher =
            (packageAllowMatcher == null) ? pkgMatcher : packageAllowMatcher.or(pkgMatcher);
      }
      if (packageAllowMatcher != null) {
        log.info("Instrumentation scope limited to packages: {}", allowedPackages);
      }
    }

    TypeExistenceChecker checker = new TypeExistenceChecker(instrumentation);

    // Group definitions that select the same classes and weave with the same advice. The advice
    // dispatches to every matching interceptor itself, so weaving it once per method is enough and
    // weaving it again runs the method body and every callback once per copy. Definitions differ in
    // which methods they select, so the copies collapse into one visit whose matcher is the union.
    java.util.Map<String, java.util.List<InterceptorDefinition>> weaveGroups =
        new java.util.LinkedHashMap<>();
    for (InterceptorDefinition definition : registry.getAllDefinitions()) {
      ClassMatcher classMatcher = definition.getPointcut().getClassMatcher();
      String groupKey = classMatcher.getMatchType() + ":" + classMatcher.getPattern()
          + ":" + definition.getAdviceMode();
      weaveGroups.computeIfAbsent(groupKey, k -> new java.util.ArrayList<>()).add(definition);
    }

    for (java.util.List<InterceptorDefinition> group : weaveGroups.values()) {
      InterceptorDefinition definition = group.get(0);
      ClassMatcher classMatcher = definition.getPointcut().getClassMatcher();

      // Before registering the type transformer, check if the target class exists.
      // Only skip EXACT_NAME matches because pattern/annotation/superclass
      // matches may apply to classes we can't predict.
      if (classMatcher.getMatchType() == ClassMatcher.MatchType.EXACT_NAME) {
        if (!checker.exists(classMatcher.getPattern())) {
          log.info(
              "Skipping interceptor '{}': target class '{}' not found on classpath",
              definition.getName(),
              classMatcher.getPattern());
          continue;
        }
      }

      net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> typeMatcher =
          buildTypeMatcher(classMatcher);
      // A class matcher that accepts every class (@args, handler, cflow, if) would otherwise
      // retransform every class already loaded in the JVM. For @args only classes that declare a
      // matching method can ever be advised, so skip the rest while revisiting loaded classes.
      if (typeMatcher != null
          && classMatcher.getMatchType() == ClassMatcher.MatchType.ANY
          && definition.getPointcut().getMethodMatcher().getMatchType()
              == com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.MatchType.ARGS) {
        com.github.cc11001100.weavergirl.api.matcher.MethodMatcher argsMatcher =
            definition.getPointcut().getMethodMatcher();
        typeMatcher =
            typeMatcher.and(
                new net.bytebuddy.matcher.ElementMatcher.Junction.AbstractBase<TypeDescription>() {
                  @Override
                  public boolean matches(TypeDescription target) {
                    return declaresArgsMatch(target, argsMatcher);
                  }
                });
      }
      // If package allowlist is specified, narrow the type matcher to only allowed packages
      if (typeMatcher != null && packageAllowMatcher != null) {
        typeMatcher = typeMatcher.and(packageAllowMatcher);
      }
      if (typeMatcher != null) {
        // Choose advice class based on match type / mode:
        //  - constructors use ConstructorAdvice (binds @Advice.Origin Constructor<?>)
        //  - ARGUMENT_REWRITE interceptors use AsyncArgumentAdvice (binds
        //    @Advice.Argument(0, readOnly=false) so setArgument(0,...) propagates)
        //  - everything else uses InterceptAdvice (binds @Advice.Origin Method).
        // ByteBuddy cannot bind both Origin Method and Constructor<?> in one class,
        // and AllArguments (even readOnly=false) does not write element mutations
        // back to parameter slots — hence the dedicated advice per concern.
        com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode mode =
            definition.getAdviceMode();
        Class<?> adviceClass;
        if (mode == com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode.FIELD) {
          adviceClass = FieldAdvice.class;
        } else if (mode == com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode.CATCH) {
          adviceClass = CatchAdvice.class;
        } else if (mode == com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode.STATIC_INIT) {
          // Static initializers have no reflective Method, so InterceptAdvice's
          // @Advice.Origin Method binding cannot be inlined into <clinit> (ByteBuddy throws
          // "Cannot represent static <clinit>() as the specified constant" at make() time).
          // StaticInitAdvice binds only @Advice.Origin Class<?>, which resolves on <clinit>.
          adviceClass = StaticInitAdvice.class;
        } else if (definition.getPointcut().getMethodMatcher().getMatchType()
            == com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.MatchType.CONSTRUCTOR) {
          adviceClass = ConstructorAdvice.class;
        } else if (mode
            == com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition.AdviceMode
                .ARGUMENT_REWRITE) {
          adviceClass = AsyncArgumentAdvice.class;
        } else {
          adviceClass = InterceptAdvice.class;
        }

        agentBuilder =
            agentBuilder
                .type(typeMatcher)
                .transform(
                    (builder, typeDescription, classLoader, module, protectionDomain) -> {
                      // Let the failure leave this callback. Byte Buddy then calls onError and does
                      // not make() or install a new class file for this type.
                      Throwable forced = FORCED_FAILURES.get(typeDescription.getName());
                      if (forced instanceof Error) {
                        throw (Error) forced;
                      }
                      if (forced instanceof RuntimeException) {
                        throw (RuntimeException) forced;
                      }
                      if (forced != null) {
                        throw new RuntimeException(forced);
                      }
                      if (mode
                              == com.github.cc11001100.weavergirl.api.interceptor
                                  .InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE
                          && classLoader == null) {
                        if (isDebugMode()) {
                          log.debug(
                              "Skipping ARGUMENT_REWRITE advice for bootstrap class: {}",
                              typeDescription.getName());
                        }
                        return builder;
                      }
                      if (transformationCount.incrementAndGet() > maxTransformations) {
                        log.warn(
                            "Max transformations ({}) reached, not transforming: {}",
                            maxTransformations,
                            typeDescription.getName());
                        return builder; // return unmodified builder
                      }
                      net.bytebuddy.matcher.ElementMatcher.Junction<
                              net.bytebuddy.description.method.MethodDescription>
                          woven = null;
                      for (InterceptorDefinition member : group) {
                        net.bytebuddy.matcher.ElementMatcher.Junction<
                                net.bytebuddy.description.method.MethodDescription>
                            memberMatcher =
                                buildMethodMatcher(member.getPointcut().getMethodMatcher());
                        woven = woven == null ? memberMatcher : woven.or(memberMatcher);
                      }
                      return builder.visit(net.bytebuddy.asm.Advice.to(adviceClass).on(woven));
                    });
      }
    }

    resettable = agentBuilder.installOn(instrumentation);
    log.info(
        "WeaverTransformer installed with {} interceptor definitions",
        registry.getAllDefinitions().size());
  }

  /**
   * Unweave: remove every interceptor and reset the transformer so already-woven classes are
   * retransformed back to their original bytecode.
   *
   * <p>Three effects, in order:
   *
   * <ol>
   *   <li>The registry is cleared (each interceptor's {@code destroy()} is invoked), so the advice
   *       inlined into still-woven classes finds nothing to dispatch while the reset happens.
   *   <li>Any {@link IntroductionRegistry} is cleared too, so mixins stop resolving.
   *   <li>{@link net.bytebuddy.agent.builder.ResettableClassFileTransformer#reset(Instrumentation,
   *       AgentBuilder.RedefinitionStrategy)} removes the installed transformer chain and asks the
   *       JVM to retransform every class it transformed, restoring the last non-agent definition
   *       (the original bytecode).
   * </ol>
   *
   * <p>The {@link InterceptorHolder} snapshot is invalidated so the next lookup rebuilds from the
   * now-empty registry instead of serving stale interceptors.
   *
   * @return the number of classes this transformer had woven (and that the JVM therefore
   *     retransforms back to their original bytecode); 0 when the transformer was never installed
   *     or had woven nothing
   */
  public int unweave() {
    if (instrumentation == null || resettable == null) {
      return 0;
    }
    registry.clear();
    if (introductionRegistry != null) {
      introductionRegistry.clear();
    }
    com.github.cc11001100.weavergirl.core.InterceptorHolder.invalidateSnapshot();
    int woven = wovenClasses.size();
    boolean resetApplied =
        resettable.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    // The reset removes the transformer, so the captured handle is no longer valid.
    resettable = null;
    wovenClasses.clear();
    transformationCount.set(0);
    if (resetApplied) {
      log.info(
          "Unwove: removed all interceptors and retransformed {} classes to original bytecode",
          woven);
    } else {
      log.info("Unwove: removed all interceptors (no classes to retransform)");
    }
    return woven;
  }

  /**
   * The classes this transformer has woven since it was installed.
   *
   * <p>Scoped to this transformer: a second transformer's classes are not reported here. A test can
   * use it to tell "the class was visited but no interceptor matched it" apart from "it was woven",
   * which a call to {@code retransformClasses} does not report by itself.
   *
   * @return an immutable snapshot of the woven class names, never null
   */
  public java.util.Set<String> getWovenClasses() {
    return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(wovenClasses));
  }

  /**
   * Retransform already-loaded classes that match any registered interceptor. This is needed when
   * the agent is attached dynamically via agentmain, because classes loaded before the agent
   * started would not be transformed.
   *
   * @return the number of classes that were retransformed
   */
  public int retransformLoadedClasses() {
    if (instrumentation == null) {
      return 0;
    }
    if (!instrumentation.isRetransformClassesSupported()) {
      log.warn("JVM does not support class retransformation");
      return 0;
    }

    Class<?>[] allLoaded = instrumentation.getAllLoadedClasses();
    List<Class<?>> toRetransform = new ArrayList<>();

    for (Class<?> clazz : allLoaded) {
      String className = clazz.getName();
      if (isNeverRewritten(className)
          || (ignoreAgentClasses
              && className.startsWith("com.github.cc11001100.weavergirl."))
          || registry.getInterceptorsForClass(className).isEmpty()) {
        continue;
      }
      // Skip array types and primitive types
      if (clazz.isArray() || clazz.isPrimitive()) {
        continue;
      }
      // Only retransform if the class can be retransformed
      if (instrumentation.isModifiableClass(clazz)) {
        toRetransform.add(clazz);
      }
    }

    int retransformed = 0;
    for (Class<?> clazz : toRetransform) {
      int recordedBefore = timesRecorded(clazz.getName());
      try {
        instrumentation.retransformClasses(clazz);
      } catch (Throwable failure) {
        // onError already stored the fault when the transform callback threw. Swallowing here
        // keeps the rest of the batch running and does not submit this class's new bytes.
        log.warn("Retransform left {} unchanged: {}", clazz.getName(), failure.getMessage());
      }
      // The JVM may swallow a transformer exception. Count only a class whose transform listener
      // actually recorded new bytes.
      if (timesRecorded(clazz.getName()) > recordedBefore) {
        retransformed++;
      }
    }
    if (retransformed > 0) {
      log.info("Retransformed {} already-loaded classes", retransformed);
    }
    return retransformed;
  }

  private static int timesRecorded(String className) {
    int seen = 0;
    for (String recorded : AgentStatus.getInstance().getTransformedClasses()) {
      if (className.equals(recorded)) {
        seen++;
      }
    }
    return seen;
  }

  /** Leave the class unchanged and keep the fault on the existing diagnostics log. */
  private static void noteTransformFailure(String typeName, Throwable throwable) {
    AgentStatus.getInstance().incrementTransformationErrorCount();
    String kind = throwable == null ? "unknown" : throwable.getClass().getName();
    String detail = throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
    AgentDiagnostics.getInstance().recordFault("TRANSFORM", typeName + " " + kind + ": " + detail);
    log.warn("Error transforming class {}: {}", typeName, detail);
  }

  /**
   * Whether {@code type} declares a method the {@code @args} matcher could select: one of its
   * parameter types at a requested index carries the annotation. Used to keep retransformation of
   * an any-class pointcut off classes that cannot match.
   */
  private static boolean declaresArgsMatch(
      TypeDescription type, com.github.cc11001100.weavergirl.api.matcher.MethodMatcher methodMatcher) {
    String annotationName = methodMatcher.getArgumentAnnotationClassName();
    int[] indexes = methodMatcher.getArgumentIndexes();
    for (net.bytebuddy.description.method.MethodDescription.InDefinedShape method :
        type.getDeclaredMethods()) {
      for (int index : indexes) {
        if (index < 0 || index >= method.getParameters().size()) {
          continue;
        }
        for (net.bytebuddy.description.annotation.AnnotationDescription annotation :
            method.getParameters().get(index).getType().asErasure().getDeclaredAnnotations()) {
          if (annotation.getAnnotationType().getName().equals(annotationName)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> buildTypeMatcher(
      ClassMatcher classMatcher) {
    switch (classMatcher.getMatchType()) {
      case EXACT_NAME:
        return named(classMatcher.getPattern());
      case NAME_PATTERN:
        return nameMatches(classMatcher.getPattern());
      case ANNOTATION:
        return isAnnotatedWith(named(classMatcher.getPattern()));
      case SUPER_CLASS:
        return hasSuperType(named(classMatcher.getPattern()));
      case INTERFACE:
        return hasSuperType(isInterface().and(named(classMatcher.getPattern())));
      case ANY:
        // @args, handler(), cflow() and if() produce a ClassMatcher.any(). Without this branch
        // the type matcher is null and the definition is silently never woven. The method matcher
        // still decides which methods are advised, so matching every concrete class is correct —
        // but retransforming every class already loaded in the JVM is not affordable, so when the
        // method matcher can only ever hit types that reference something specific, only revisit
        // those.
        return not(isInterface());
      default:
        log.warn("Unsupported class match type: {}", classMatcher.getMatchType());
        return null;
    }
  }

  private net.bytebuddy.matcher.ElementMatcher.Junction<
          net.bytebuddy.description.method.MethodDescription>
      buildMethodMatcher(com.github.cc11001100.weavergirl.api.matcher.MethodMatcher methodMatcher) {
    net.bytebuddy.matcher.ElementMatcher.Junction<
            net.bytebuddy.description.method.MethodDescription>
        userMatcher;
    switch (methodMatcher.getMatchType()) {
      case EXACT_NAME:
        if ("<clinit>".equals(methodMatcher.getPattern())) {
          // <clinit> must be selected via isTypeInitializer(); ByteBuddy's named("<clinit>")
          // does NOT match a type initializer, so EXACT_NAME would silently select zero methods
          // and the @OnStaticInit weave would be dropped without a trace.
          userMatcher = isTypeInitializer();
        } else {
          userMatcher = named(methodMatcher.getPattern());
        }
        break;
      case NAME_PATTERN:
        userMatcher = nameMatches(methodMatcher.getPattern());
        break;
      case ANNOTATION:
        userMatcher = isAnnotatedWith(named(methodMatcher.getPattern()));
        break;
      case SIGNATURE:
        // Pattern is "methodName(param1,param2)" — match name AND parameter types.
        // Previously only matched parameter COUNT; now matches each parameter's
        // type by fully-qualified name, so doGet(HttpServletRequest,HttpServletResponse)
        // does NOT match doGet() even though both have the same method name.
        String sigPattern = methodMatcher.getPattern();
        int parenIdx = sigPattern.indexOf('(');
        if (parenIdx < 0) {
          userMatcher = named(sigPattern);
        } else {
          String methodName = sigPattern.substring(0, parenIdx);
          String paramPart = sigPattern.substring(parenIdx + 1, sigPattern.length() - 1);
          userMatcher = named(methodName);
          if (paramPart.isEmpty()) {
            userMatcher = userMatcher.and(takesArguments(0));
          } else {
            String[] paramTypes = paramPart.split(",");
            userMatcher = userMatcher.and(takesArguments(paramTypes.length));
            for (int i = 0; i < paramTypes.length; i++) {
              String typeName = paramTypes[i].trim();
              if (!typeName.isEmpty()) {
                userMatcher = userMatcher.and(takesArgument(i, named(typeName)));
              }
            }
          }
        }
        if (isDebugMode()) {
          log.debug("SIGNATURE matcher pattern={} -> {}", sigPattern, userMatcher);
        }
        break;
      case ANY:
        userMatcher = isMethod();
        break;
      case ARGS:
        // @args: match methods whose parameter type at one of the given indexes carries the
        // annotation. Without this branch the switch fell through to the default and matched
        // every method, so an @args pointcut instrumented far more than it selected.
        userMatcher = isMethod();
        for (int index : methodMatcher.getArgumentIndexes()) {
          userMatcher = userMatcher.and(hasParameterTypeAnnotatedAt(index, methodMatcher.getPattern()));
        }
        break;
      case CONSTRUCTOR:
        // Match constructors. Pattern is "<init>" for any constructor,
        // or "<init>(param1,param2)" for constructors with specific parameter types.
        userMatcher = isConstructor();
        String ctorPattern = methodMatcher.getPattern();
        int ctorParenIdx = ctorPattern.indexOf('(');
        if (ctorParenIdx >= 0) {
          String paramPart = ctorPattern.substring(ctorParenIdx + 1, ctorPattern.length() - 1);
          if (paramPart.isEmpty()) {
            userMatcher = userMatcher.and(takesArguments(0));
          } else {
            String[] paramTypes = paramPart.split(",");
            userMatcher = userMatcher.and(takesArguments(paramTypes.length));
            for (int i = 0; i < paramTypes.length; i++) {
              String typeName = paramTypes[i].trim();
              if (!typeName.isEmpty()) {
                // named() matches the dot-separated binary name (same as the SIGNATURE
                // branch below); toInternalName's slash form never matches here.
                userMatcher = userMatcher.and(takesArgument(i, named(typeName)));
              }
            }
          }
        }
        if (isDebugMode()) {
          log.debug("CONSTRUCTOR matcher pattern={} -> {}", ctorPattern, userMatcher);
        }
        break;
      case CFIELD_GET:
      case CFIELD_SET:
        // Field accessors are synthetic ByteBuddy-generated methods.
        // Match by field-name substring so synthetic accessor names resolve correctly.
        userMatcher = nameMatches(".*" + Pattern.quote(methodMatcher.getPattern()) + ".*");
        break;
      default:
        userMatcher = isMethod();
    }

    // Always exclude bridge, native, and abstract methods.
    // For CFIELD_GET / CFIELD_SET we must NOT exclude synthetic methods,
    // because ByteBuddy generates synthetic field-accessor stubs.
    if (methodMatcher.getMatchType() != com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.MatchType.CFIELD_GET
        && methodMatcher.getMatchType() != com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.MatchType.CFIELD_SET) {
      userMatcher = userMatcher.and(not(isSynthetic()));
    }
    return userMatcher
        .and(not(isBridge()))
        .and(not(isNative()))
        .and(not(isAbstract()));
  }

  /**
   * Matches methods whose parameter at {@code index} has a declared type annotated with {@code
   * annotationName}. The annotation is read from the parameter type itself (AspectJ {@code @args}
   * semantics), not from the parameter declaration.
   */
  private static net.bytebuddy.matcher.ElementMatcher.Junction<
          net.bytebuddy.description.method.MethodDescription>
      hasParameterTypeAnnotatedAt(int index, String annotationName) {
    return hasParameters(
        whereAny(
            new net.bytebuddy.matcher.ElementMatcher<ParameterDescription>() {
              @Override
              public boolean matches(ParameterDescription target) {
                if (target.getIndex() != index) {
                  return false;
                }
                for (AnnotationDescription annotation :
                    target.getType().asErasure().getDeclaredAnnotations()) {
                  if (annotation.getAnnotationType().getName().equals(annotationName)) {
                    return true;
                  }
                }
                return false;
              }
            }));
  }

}
