package com.github.cc11001100.weavergirl.api.introduction;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntroductionRegistryTest {

  interface Sample {
    void doIt();
  }

  static class SampleImpl implements Sample {
    public void doIt() {}
  }

  private IntroductionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new IntroductionRegistry();
  }

  private IntroductionDefinition definition(String name, ClassMatcher matcher) {
    return new IntroductionDefinition(name, matcher, Sample.class, new SampleImpl());
  }

  @Test
  void register_nullDefinition_throws() {
    assertThrows(IllegalArgumentException.class, () -> registry.register(null));
  }

  @Test
  void register_addsToAllDefinitions() {
    IntroductionDefinition def = definition("d1", ClassMatcher.byName("com.example.Foo"));
    registry.register(def);
    assertEquals(List.of(def), registry.getAllDefinitions());
  }

  @Test
  void getAllDefinitions_returnsIndependentCopy() {
    IntroductionDefinition def = definition("d1", ClassMatcher.byName("com.example.Foo"));
    registry.register(def);
    List<IntroductionDefinition> snapshot = registry.getAllDefinitions();
    snapshot.clear();
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void unregister_nullName_returnsFalse() {
    assertFalse(registry.unregister(null));
  }

  @Test
  void unregister_unknownName_returnsFalse() {
    registry.register(definition("d1", ClassMatcher.byName("com.example.Foo")));
    assertFalse(registry.unregister("does-not-exist"));
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void unregister_existingName_removesAndReturnsTrue() {
    registry.register(definition("d1", ClassMatcher.byName("com.example.Foo")));
    assertTrue(registry.unregister("d1"));
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  @Test
  void getDefinitionsForClass_returnsOnlyMatchingDefinitions() {
    IntroductionDefinition matching = definition("d1", ClassMatcher.byName("com.example.Foo"));
    IntroductionDefinition nonMatching = definition("d2", ClassMatcher.byName("com.example.Bar"));
    registry.register(matching);
    registry.register(nonMatching);

    List<IntroductionDefinition> result = registry.getDefinitionsForClass("com.example.Foo");

    assertEquals(List.of(matching), result);
  }

  @Test
  void getDefinitionsForClass_noMatches_returnsEmptyList() {
    registry.register(definition("d1", ClassMatcher.byName("com.example.Foo")));
    assertTrue(registry.getDefinitionsForClass("com.example.Other").isEmpty());
  }

  @Test
  void getDefinitionsForClass_multipleMatches_returnsAllInOrder() {
    IntroductionDefinition d1 = definition("d1", ClassMatcher.byNamePattern("com\\.example\\..*"));
    IntroductionDefinition d2 = definition("d2", ClassMatcher.byNamePattern("com\\.example\\..*"));
    registry.register(d1);
    registry.register(d2);

    assertEquals(List.of(d1, d2), registry.getDefinitionsForClass("com.example.Foo"));
  }

  @Test
  void clear_removesAllDefinitions() {
    registry.register(definition("d1", ClassMatcher.byName("com.example.Foo")));
    registry.register(definition("d2", ClassMatcher.byName("com.example.Bar")));
    registry.clear();
    assertTrue(registry.getAllDefinitions().isEmpty());
  }
}
