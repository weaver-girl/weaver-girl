package com.github.cc11001100.weavergirl.core.transformer;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptAdvice;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Core transformer that registers ByteBuddy type transformers
 * based on registered interceptor definitions.
 */
public class WeaverTransformer {

    private static final Logger log = LoggerFactory.getLogger(WeaverTransformer.class);

    private final InterceptorRegistry registry;
    private Instrumentation instrumentation;
    private List<String> excludedClassPatterns = Collections.emptyList();
    private boolean ignoreAgentClasses = true;
    private WeaverConfig weaverConfig;
    private final AtomicInteger transformationCount = new AtomicInteger(0);
    private int maxTransformations = 10000; // default

    public WeaverTransformer(InterceptorRegistry registry) {
        this.registry = registry;
    }

    public void setExcludedClassPatterns(List<String> patterns) {
        this.excludedClassPatterns = patterns != null ? patterns : Collections.emptyList();
    }

    /**
     * Set whether to ignore the agent's own classes, shaded dependencies,
     * and JDK internals. Defaults to true; set to false in test environments
     * where test target classes live inside the weavergirl package tree.
     */
    public void setIgnoreAgentClasses(boolean ignoreAgentClasses) {
        this.ignoreAgentClasses = ignoreAgentClasses;
    }

    /**
     * Set the WeaverConfig for scope control (onlyInterceptPackages, maxTransformations).
     */
    public void setWeaverConfig(WeaverConfig weaverConfig) {
        this.weaverConfig = weaverConfig;
        if (weaverConfig != null) {
            if (weaverConfig.getMaxTransformations() != null) {
                this.maxTransformations = weaverConfig.getMaxTransformations();
            }
        }
    }

    /**
     * Set the maximum number of classes that may be transformed.
     */
    public void setMaxTransformations(int max) {
        this.maxTransformations = max;
    }

    private boolean isDebugMode() {
        return Boolean.getBoolean("weavergirl.debug");
    }

    /**
     * Install this transformer onto the given Instrumentation instance.
     */
    public void install(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;

        // Inject helper classes into Bootstrap ClassLoader so advice code
        // is visible when instrumenting java.* / javax.* classes
        BootstrapInjection bootstrapInjection = new BootstrapInjection();
        bootstrapInjection.inject(instrumentation);

        // Build the ignore matcher: always exclude JDK internals;
        // optionally exclude the agent's own classes and shaded dependencies
        // to prevent ClassCircularityError.
        net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> excludeMatcher = nameStartsWith("sun.")
                .or(nameStartsWith("jdk.internal."))
                .or(nameStartsWith("com.sun."));

        if (ignoreAgentClasses) {
            excludeMatcher = excludeMatcher
                    .or(nameStartsWith("com.github.cc11001100.weavergirl."))
                    .or(nameStartsWith("com.github.cc11001100.weavergirl.shade."));
        }

        for (String pattern : excludedClassPatterns) {
            excludeMatcher = excludeMatcher.or(nameMatches(pattern));
        }

        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .ignore(excludeMatcher)
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.InjectionStrategy.UsingInstrumentation(
                        instrumentation, new java.io.File(System.getProperty("java.io.tmpdir"))))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                  JavaModule module, boolean loaded, DynamicType dynamicType) {
                        AgentStatus.getInstance().incrementTransformationCount();
                        AgentStatus.getInstance().addTransformedClass(typeDescription.getName());
                        log.info("Transformed class: {}", typeDescription.getName());
                        if (isDebugMode()) {
                            List<String> matchedNames = registry.getInterceptorsForClass(typeDescription.getName())
                                    .stream().map(InterceptorDefinition::getName).collect(Collectors.toList());
                            log.debug("  Interceptors matching {}: {}", typeDescription.getName(), matchedNames);
                        }
                    }

                    @Override
                    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                                          JavaModule module, boolean loaded) {
                        if (isDebugMode()) {
                            log.debug("Ignored class (no matching interceptor): {}", typeDescription.getName());
                        }
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        JavaModule module, boolean loaded, Throwable throwable) {
                        AgentStatus.getInstance().incrementTransformationErrorCount();
                        log.warn("Error transforming class {}: {}", typeName, throwable.getMessage());
                    }
                });

        // If onlyInterceptPackages is specified, only match classes in those packages
        List<String> allowedPackages = weaverConfig != null ? weaverConfig.getOnlyInterceptPackages() : null;
        net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> packageAllowMatcher = null;
        if (allowedPackages != null && !allowedPackages.isEmpty()) {
            for (String pkg : allowedPackages) {
                String prefix = pkg.endsWith(".") ? pkg : pkg + ".";
                net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> pkgMatcher = nameStartsWith(prefix);
                packageAllowMatcher = (packageAllowMatcher == null) ? pkgMatcher : packageAllowMatcher.or(pkgMatcher);
            }
            if (packageAllowMatcher != null) {
                log.info("Instrumentation scope limited to packages: {}", allowedPackages);
            }
        }

        TypeExistenceChecker checker = new TypeExistenceChecker(instrumentation);

        for (InterceptorDefinition definition : registry.getAllDefinitions()) {
            ClassMatcher classMatcher = definition.getPointcut().getClassMatcher();

            // Before registering the type transformer, check if the target class exists.
            // Only skip EXACT_NAME matches because pattern/annotation/superclass
            // matches may apply to classes we can't predict.
            if (classMatcher.getMatchType() == ClassMatcher.MatchType.EXACT_NAME) {
                if (!checker.exists(classMatcher.getPattern())) {
                    log.info("Skipping interceptor '{}': target class '{}' not found on classpath",
                            definition.getName(), classMatcher.getPattern());
                    continue;
                }
            }

            net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> typeMatcher = buildTypeMatcher(classMatcher);
            // If package allowlist is specified, narrow the type matcher to only allowed packages
            if (typeMatcher != null && packageAllowMatcher != null) {
                typeMatcher = typeMatcher.and(packageAllowMatcher);
            }
            if (typeMatcher != null) {
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                            if (transformationCount.incrementAndGet() > maxTransformations) {
                                log.warn("Max transformations ({}) reached, not transforming: {}",
                                        maxTransformations, typeDescription.getName());
                                return builder; // return unmodified builder
                            }
                            return builder.visit(net.bytebuddy.asm.Advice.to(InterceptAdvice.class)
                                    .on(buildMethodMatcher(definition.getPointcut().getMethodMatcher())));
                        });
            }
        }

        agentBuilder.installOn(instrumentation);
        log.info("WeaverTransformer installed with {} interceptor definitions", registry.getAllDefinitions().size());
    }

    /**
     * Retransform already-loaded classes that match any registered interceptor.
     * This is needed when the agent is attached dynamically via agentmain,
     * because classes loaded before the agent started would not be transformed.
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
            if (registry.getInterceptorsForClass(className).isEmpty()) {
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

        if (!toRetransform.isEmpty()) {
            try {
                instrumentation.retransformClasses(toRetransform.toArray(new Class<?>[0]));
                log.info("Retransformed {} already-loaded classes", toRetransform.size());
            } catch (Exception e) {
                log.error("Failed to retransform classes: {}", e.getMessage());
            }
        }

        return toRetransform.size();
    }

    private net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> buildTypeMatcher(ClassMatcher classMatcher) {
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
            default:
                log.warn("Unsupported class match type: {}", classMatcher.getMatchType());
                return null;
        }
    }

    private net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> buildMethodMatcher(
            com.github.cc11001100.weavergirl.api.matcher.MethodMatcher methodMatcher) {
        net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> userMatcher;
        switch (methodMatcher.getMatchType()) {
            case EXACT_NAME:
                userMatcher = named(methodMatcher.getPattern());
                break;
            case NAME_PATTERN:
                userMatcher = nameMatches(methodMatcher.getPattern());
                break;
            case ANY:
                userMatcher = isMethod();
                break;
            default:
                userMatcher = isMethod();
        }
        // Always exclude bridge, synthetic, native, and abstract methods
        // These cannot be or should not be instrumented by ByteBuddy Advice
        return userMatcher
                .and(not(isBridge()))
                .and(not(isSynthetic()))
                .and(not(isNative()))
                .and(not(isAbstract()));
    }
}
