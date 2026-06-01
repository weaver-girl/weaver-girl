// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/transformer/WeaverTransformer.java
package com.github.cc11001100.weavergirl.core.transformer;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptAdvice;
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
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Core transformer that registers ByteBuddy type transformers
 * based on registered interceptor definitions.
 */
public class WeaverTransformer {

    private static final Logger log = LoggerFactory.getLogger(WeaverTransformer.class);

    private final InterceptorRegistry registry;
    private Instrumentation instrumentation;

    public WeaverTransformer(InterceptorRegistry registry) {
        this.registry = registry;
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

        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.InjectionStrategy.UsingInstrumentation(
                        instrumentation, new java.io.File(System.getProperty("java.io.tmpdir"))))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                  JavaModule module, boolean loaded, DynamicType dynamicType) {
                        AgentStatus.getInstance().incrementTransformationCount();
                        log.info("Transformed class: {}", typeDescription.getName());
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        JavaModule module, boolean loaded, Throwable throwable) {
                        AgentStatus.getInstance().incrementTransformationErrorCount();
                        log.warn("Error transforming class {}: {}", typeName, throwable.getMessage());
                    }
                });

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
            if (typeMatcher != null) {
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                builder.visit(net.bytebuddy.asm.Advice.to(InterceptAdvice.class)
                                        .on(buildMethodMatcher(definition.getPointcut().getMethodMatcher())))
                        );
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
        switch (methodMatcher.getMatchType()) {
            case EXACT_NAME:
                return named(methodMatcher.getPattern());
            case NAME_PATTERN:
                return nameMatches(methodMatcher.getPattern());
            case ANY:
                return isMethod();
            default:
                return isMethod();
        }
    }
}
