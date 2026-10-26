package com.github.cc11001100.weavergirl.api.matcher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.junit.jupiter.api.Test;

class ClassMatcherTest {

  @Test
  void byName_exactMatch() {
    ClassMatcher m = ClassMatcher.byName("com.example.Service");
    assertTrue(m.matches("com.example.Service"));
    assertFalse(m.matches("com.example.Other"));
    assertFalse(m.matches("com.example.service"));
  }

  @Test
  void byNamePattern_regexMatch() {
    ClassMatcher m = ClassMatcher.byNamePattern("com\\.example\\..*Service");
    assertTrue(m.matches("com.example.UserService"));
    assertTrue(m.matches("com.example.sub.OrderService"));
    assertFalse(m.matches("org.example.Service"));
  }

  @Test
  void byAnnotation_matchesAnnotationClassItself() {
    ClassMatcher m = ClassMatcher.byAnnotation("com.example.Trace");
    assertTrue(m.matches("com.example.Trace"));
    assertFalse(m.matches("com.example.Service"));
  }

  @Test
  void bySuperClass_neverMatchesFromStringOnly() {
    ClassMatcher m = ClassMatcher.bySuperClass("com.example.BaseService");
    assertFalse(m.matches("com.example.Service"));
  }

  @Test
  void byInterface_neverMatchesFromStringOnly() {
    ClassMatcher m = ClassMatcher.byInterface("java.io.Serializable");
    assertFalse(m.matches("java.io.Serializable"));
  }

  @Test
  void any_matchesAll() {
    ClassMatcher m = ClassMatcher.any();
    assertTrue(m.matches("anything"));
    assertTrue(m.matches(""));
  }

  @Test
  void equalsAndHashCode() {
    ClassMatcher a = ClassMatcher.byName("com.example.Service");
    ClassMatcher b = ClassMatcher.byName("com.example.Service");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());

    ClassMatcher c = ClassMatcher.byName("com.example.Other");
    assertNotEquals(a, c);
  }

  @Test
  void toString_containsTypeAndPattern() {
    ClassMatcher m = ClassMatcher.byNamePattern("com\\.example\\..*");
    assertTrue(m.toString().contains("NAME_PATTERN"));
    assertTrue(m.toString().contains("com\\.example\\..*"));
  }

  @Test
  void loadedClassMatchCoversNamePatternAnnotationSuperTypeAndInterface() {
    assertTrue(ClassMatcher.byName(String.class.getName()).matches(String.class));
    assertFalse(ClassMatcher.byName(String.class.getName()).matches(Integer.class));
    assertTrue(ClassMatcher.byNamePattern("java\\.lang\\.S.*").matches(String.class));
    assertFalse(ClassMatcher.byNamePattern("java\\.lang\\.I.*").matches(String.class));
    assertTrue(ClassMatcher.any().matches(String.class));

    assertTrue(ClassMatcher.byAnnotation(Marked.class.getName()).matches(MarkedType.class));
    assertFalse(ClassMatcher.byAnnotation(Marked.class.getName()).matches(String.class));
    assertFalse(ClassMatcher.byAnnotation("no.such.Annotation").matches(MarkedType.class));
    assertFalse(ClassMatcher.byAnnotation(String.class.getName()).matches(String.class));

    assertTrue(ClassMatcher.bySuperClass(Number.class.getName()).matches(Integer.class));
    assertFalse(ClassMatcher.bySuperClass(Integer.class.getName()).matches(Integer.class));
    assertFalse(ClassMatcher.bySuperClass("no.such.Type").matches(Integer.class));

    assertTrue(ClassMatcher.byInterface(Serializable.class.getName()).matches(String.class));
    assertFalse(ClassMatcher.byInterface(String.class.getName()).matches(String.class));
  }

  @Test
  void equalityRejectsADifferentTypeAndTheSameInstanceMatchesItself() {
    ClassMatcher matcher = ClassMatcher.byName("com.example.Service");

    assertEquals(matcher, matcher);
    assertNotEquals(matcher, "com.example.Service");
    assertNotEquals(ClassMatcher.byName("com.example.Service"), ClassMatcher.any());
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface Marked {}

  @Marked
  static final class MarkedType {}
}
