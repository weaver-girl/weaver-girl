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
