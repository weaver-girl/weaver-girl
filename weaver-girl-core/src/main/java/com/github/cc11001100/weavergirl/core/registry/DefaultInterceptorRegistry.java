// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java
package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
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
    private final ConcurrentHashMap<String, List<InterceptorDefinition>> classIndex = new ConcurrentHashMap<>();
    private volatile boolean indexDirty = true;

    @Override
    public void register(InterceptorDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null InterceptorDefinition, ignoring");
            return;
        }
        definitions.add(definition);
        indexDirty = true;
        log.info("Registered interceptor: {}", definition.getName());
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
        if (indexDirty) {
            rebuildIndex();
        }
        List<InterceptorDefinition> cached = classIndex.get(className);
        return cached != null ? cached : Collections.emptyList();
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
        return Collections.unmodifiableList(definitions);
    }

    @Override
    public boolean unregister(String name) {
        if (name == null) {
            return false;
        }
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
        classIndex.clear();
        indexDirty = true;
    }

    private synchronized void rebuildIndex() {
        if (!indexDirty) {
            return;
        }
        ConcurrentHashMap<String, List<InterceptorDefinition>> newIndex = new ConcurrentHashMap<>();
        for (InterceptorDefinition def : definitions) {
            String pattern = def.getPointcut().getClassMatcher().getPattern();
            newIndex.computeIfAbsent(pattern, k -> new ArrayList<>()).add(def);
        }
        // Sort each list by priority
        for (List<InterceptorDefinition> list : newIndex.values()) {
            list.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
        }
        classIndex.clear();
        classIndex.putAll(newIndex);
        indexDirty = false;
    }
}
