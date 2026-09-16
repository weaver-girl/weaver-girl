package com.github.cc11001100.weavergirl.api.matcher;

import static org.junit.jupiter.api.Assertions.*;

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
}
