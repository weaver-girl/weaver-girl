// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java
package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default thread-safe implementation of InterceptorRegistry.
 * Manages all registered interceptor definitions.
 */
public class DefaultInterceptorRegistry implements InterceptorRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterceptorRegistry.class);

    private final List<InterceptorDefinition> definitions = new CopyOnWriteArrayList<>();

    @Override
    public void register(InterceptorDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null InterceptorDefinition, ignoring");
            return;
        }
        definitions.add(definition);
        log.info("Registered interceptor: {}", definition.getName());
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
        List<InterceptorDefinition> matched = new ArrayList<>();
        for (InterceptorDefinition def : definitions) {
            if (def.getPointcut().getClassMatcher().matches(className)) {
                matched.add(def);
            }
        }
        matched.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
        return matched;
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
        return Collections.unmodifiableList(definitions);
    }

    /**
     * Clear all registered definitions. For testing purposes.
     */
    public void clear() {
        definitions.clear();
    }
}
