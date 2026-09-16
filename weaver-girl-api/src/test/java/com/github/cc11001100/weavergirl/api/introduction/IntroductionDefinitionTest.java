package com.github.cc11001100.weavergirl.api.introduction;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import org.junit.jupiter.api.Test;

class IntroductionDefinitionTest {

  interface Sample {
    void doIt();
  }

  static class SampleImpl implements Sample {
    public void doIt() {}
  }

  private final ClassMatcher matcher = ClassMatcher.byName("com.example.Foo");
  private final Object delegate = new SampleImpl();

  @Test
  void constructor_populatesAllGetters() {
    IntroductionDefinition def = new IntroductionDefinition("name1", matcher, Sample.class, delegate);
    assertEquals("name1", def.getName());
    assertSame(matcher, def.getClassMatcher());
    assertEquals(Sample.class, def.getIntroductionType());
    assertSame(delegate, def.getDelegate());
  }

  @Test
  void constructor_nullName_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IntroductionDefinition(null, matcher, Sample.class, delegate));
    assertTrue(ex.getMessage().contains("name"));
  }

  @Test
  void constructor_emptyName_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntroductionDefinition("", matcher, Sample.class, delegate));
  }

  @Test
  void constructor_nullClassMatcher_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IntroductionDefinition("name1", null, Sample.class, delegate));
    assertTrue(ex.getMessage().contains("name1"));
  }

  @Test
  void constructor_nullIntroductionType_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IntroductionDefinition("name1", matcher, null, delegate));
    assertTrue(ex.getMessage().contains("name1"));
  }

  @Test
  void constructor_nullDelegate_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IntroductionDefinition("name1", matcher, Sample.class, null));
    assertTrue(ex.getMessage().contains("name1"));
  }

  @Test
  void constructor_introductionTypeNotInterface_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new IntroductionDefinition("name1", matcher, SampleImpl.class, delegate));
    assertTrue(ex.getMessage().contains(SampleImpl.class.getName()));
  }
}
