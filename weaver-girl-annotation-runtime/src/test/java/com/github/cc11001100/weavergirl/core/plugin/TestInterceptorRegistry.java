package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal in-memory {@link InterceptorRegistry} for annotation-runtime tests.
 *
 * <p>Keeps the annotation-runtime test scope free of the core engine: same-name registration
 * replaces, {@code getAllDefinitions} is unmodifiable, {@code getInterceptorsForClass} filters by
 * class matcher and sorts by priority — the only semantics loader tests rely on.
 *
 * <p>Public so the scanner test package can reuse the same fake.
 */
public final class TestInterceptorRegistry implements InterceptorRegistry {

  private final List<InterceptorDefinition> definitions = new ArrayList<>();

  @Override
  public synchronized void register(InterceptorDefinition definition) {
    if (definition == null) {
      return;
    }
    definitions.removeIf(d -> d.getName().equals(definition.getName()));
    definitions.add(definition);
  }

  @Override
  public synchronized boolean unregister(String name) {
    return definitions.removeIf(d -> d.getName().equals(name));
  }

  @Override
  public synchronized List<InterceptorDefinition> getInterceptorsForClass(String className) {
    List<InterceptorDefinition> result = new ArrayList<>();
    for (InterceptorDefinition def : definitions) {
      try {
        if (def.getPointcut() != null
            && def.getPointcut().getClassMatcher() != null
            && def.getPointcut().getClassMatcher().matches(className)) {
          result.add(def);
        }
      } catch (RuntimeException ignored) {
        // A test fake must never fail a lookup on matcher edge cases.
      }
    }
    result.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
    return result;
  }

  @Override
  public synchronized List<InterceptorDefinition> getAllDefinitions() {
    return Collections.unmodifiableList(new ArrayList<>(definitions));
  }
}
