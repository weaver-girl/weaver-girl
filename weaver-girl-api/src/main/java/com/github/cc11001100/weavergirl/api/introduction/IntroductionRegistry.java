package com.github.cc11001100.weavergirl.api.introduction;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry that holds {@link IntroductionDefinition}s and provides lookup by class name.
 *
 * <p>This is a simple in-memory registry. Thread-safe for concurrent registration and lookup.
 *
 * @since 2.0.0
 */
public class IntroductionRegistry {

  private final List<IntroductionDefinition> definitions = new CopyOnWriteArrayList<>();

  public IntroductionRegistry() {}

  /**
   * Register an introduction definition.
   *
   * @param definition the introduction to register
   */
  public void register(IntroductionDefinition definition) {
    if (definition == null) {
      throw new IllegalArgumentException("IntroductionDefinition must not be null");
    }
    definitions.add(definition);
  }

  /**
   * Unregister an introduction by name.
   *
   * @param name the definition name
   * @return true if an introduction was removed
   */
  public boolean unregister(String name) {
    if (name == null) {
      return false;
    }
    return definitions.removeIf(d -> d.getName().equals(name));
  }

  /**
   * Return all registered introductions.
   *
   * @return an unmodifiable view of all definitions
   */
  public List<IntroductionDefinition> getAllDefinitions() {
    return new ArrayList<>(definitions);
  }

  /**
   * Return introduction definitions that match the given class name.
   *
   * @param className the class name to match
   * @return matching definitions
   */
  public List<IntroductionDefinition> getDefinitionsForClass(String className) {
    List<IntroductionDefinition> matched = new ArrayList<>();
    for (IntroductionDefinition def : definitions) {
      if (def.getClassMatcher().matches(className)) {
        matched.add(def);
      }
    }
    return matched;
  }

  /**
   * Clear all registered introductions.
   */
  public void clear() {
    definitions.clear();
  }
}
