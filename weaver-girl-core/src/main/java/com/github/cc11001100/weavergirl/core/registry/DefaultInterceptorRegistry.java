package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default thread-safe implementation of InterceptorRegistry.
 * Uses a ConcurrentHashMap index for fast class-level lookup.
 */
public class DefaultInterceptorRegistry implements InterceptorRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterceptorRegistry.class);

    private final List<InterceptorDefinition> definitions = new CopyOnWriteArrayList<>();
    private volatile Map<String, List<InterceptorDefinition>> classIndex = new ConcurrentHashMap<>();
    private volatile boolean indexDirty = true;

    @Override
    public void register(InterceptorDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null InterceptorDefinition, ignoring. Call stack:", new Exception());
            return;
        }
        synchronized (this) {
            definitions.removeIf(d -> d.getName().equals(definition.getName()));
            definitions.add(definition);
            indexDirty = true;
        }
        log.info("Registered interceptor: {}", definition.getName());
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
        ValidationUtils.requireNonEmpty(className, "className");
        if (indexDirty) {
            rebuildIndex();
        }
        List<InterceptorDefinition> result = new ArrayList<>(classIndex.getOrDefault(className, Collections.emptyList()));

        // Also check non-EXACT_NAME matchers (pattern, annotation, super, interface)
        // These can't be indexed by class name, so we scan all definitions
        for (InterceptorDefinition def : definitions) {
            ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
            if (classMatcher.getMatchType() != ClassMatcher.MatchType.EXACT_NAME) {
                if (classMatcher.matches(className)) {
                    if (!result.contains(def)) {
                        result.add(def);
                    }
                } else if (matchesByReflection(classMatcher, className)) {
                    // INTERFACE/SUPER_CLASS/ANNOTATION matchers can't be decided by
                    // ClassMatcher.matches(String) (it returns false for those types).
                    // Fall back to a reflective runtime check so that dynamic-attach
                    // retransformation can select already-loaded implementors
                    // (e.g. ThreadPoolExecutor implementing ExecutorService). At
                    // premain time classes are matched by ByteBuddy's type matcher
                    // during load, so this path only matters for retransform.
                    if (!result.contains(def)) {
                        result.add(def);
                    }
                }
            }
        }

        // Sort by priority
        result.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
        return result;
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
        return Collections.unmodifiableList(definitions);
    }

    @Override
    public boolean unregister(String name) {
        ValidationUtils.requireNonEmpty(name, "name");
        boolean removed = definitions.removeIf(d -> name.equals(d.getName()));
        if (removed) {
            indexDirty = true;
            log.info("Unregistered interceptor: {}", name);
        }
        return removed;
    }

    /**
     * Clear all registered definitions. For testing purposes.
     */
    public void clear() {
        definitions.clear();
        classIndex = new ConcurrentHashMap<>();
        indexDirty = true;
    }

    /**
     * Reflective runtime check for match types that {@link ClassMatcher#matches(String)}
     * cannot decide (INTERFACE, SUPER_CLASS, ANNOTATION). Used by
     * {@link #getInterceptorsForClass(String)} so that dynamic-attach retransformation
     * can select already-loaded implementors/subtypes/annotated classes.
     *
     * <p>Loads the candidate class via {@code Class.forName} (no initialization). Since
     * this is only consulted for classes reported by {@code Instrumentation.getAllLoadedClasses()}
     * during retransformation, the class is already loaded and this is a no-op lookup.</p>
     *
     * @param classMatcher the matcher with a non-name match type
     * @param className    the fully-qualified class name to test
     * @return true if the loaded class satisfies the matcher, false otherwise (including
     *         when the class cannot be loaded)
     */
    private boolean matchesByReflection(ClassMatcher classMatcher, String className) {
        Class<?> candidate;
        try {
            candidate = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            // ClassNotFoundException, NoClassDefFoundError, SecurityException, etc.
            // — common for synthetic/hidden JVM-internal classes encountered during
            // retransformation scanning; treat as non-matching.
            return false;
        }
        switch (classMatcher.getMatchType()) {
            case INTERFACE: {
                Class<?> iface;
                try {
                    iface = Class.forName(classMatcher.getPattern(), false,
                            Thread.currentThread().getContextClassLoader());
                } catch (Throwable t) {
                    return false;
                }
                return iface.isInterface() && iface.isAssignableFrom(candidate);
            }
            case SUPER_CLASS: {
                Class<?> sup;
                try {
                    sup = Class.forName(classMatcher.getPattern(), false,
                            Thread.currentThread().getContextClassLoader());
                } catch (Throwable t) {
                    return false;
                }
                return sup.isAssignableFrom(candidate) && !sup.equals(candidate);
            }
            case ANNOTATION:
                // Annotation presence requires resolving the annotation type and reflecting
                // over the candidate; keep this path cheap by checking only direct/inheritable
                // annotations via the JVM-cached annotation array.
                Class<?> annoType;
                try {
                    annoType = Class.forName(classMatcher.getPattern(), false,
                            Thread.currentThread().getContextClassLoader());
                } catch (Throwable t) {
                    return false;
                }
                if (!annoType.isAnnotation()) {
                    return false;
                }
                @SuppressWarnings("unchecked")
                Class<? extends java.lang.annotation.Annotation> annoClass =
                        (Class<? extends java.lang.annotation.Annotation>) annoType;
                return candidate.isAnnotationPresent(annoClass);
            default:
                return false;
        }
    }

    private synchronized void rebuildIndex() {
        if (!indexDirty) {
            return;
        }
        Map<String, List<InterceptorDefinition>> newIndex = new ConcurrentHashMap<>();
        for (InterceptorDefinition def : definitions) {
            String pattern = def.getPointcut().getClassMatcher().getPattern();
            newIndex.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(def);
        }
        // Sort each list by priority
        for (List<InterceptorDefinition> list : newIndex.values()) {
            list.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
        }
        classIndex = newIndex;  // atomic swap
        indexDirty = false;
    }
}
