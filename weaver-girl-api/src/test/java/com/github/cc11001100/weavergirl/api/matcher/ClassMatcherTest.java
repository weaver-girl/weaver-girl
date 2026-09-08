package com.github.cc11001100.weavergirl.api.matcher;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClassMatcherTest {

  @Test
  void byName_exactMatch_returnsTrue() {
    ClassMatcher matcher = ClassMatcher.byName("com.example.TargetService");
    assertTrue(matcher.matches("com.example.TargetService"));
  }

  @Test
  void byName_noMatch_returnsFalse() {
    ClassMatcher matcher = ClassMatcher.byName("com.example.TargetService");
    assertFalse(matcher.matches("com.example.OtherService"));
  }

  @Test
  void byNamePattern_matchingPattern_returnsTrue() {
    ClassMatcher matcher = ClassMatcher.byNamePattern("com\\.example\\..*Service");
    assertTrue(matcher.matches("com.example.UserService"));
    assertTrue(matcher.matches("com.example.OrderService"));
  }

  @Test
  void byNamePattern_nonMatchingPattern_returnsFalse() {
    ClassMatcher matcher = ClassMatcher.byNamePattern("com\\.example\\..*Service");
    assertFalse(matcher.matches("com.example.Util"));
  }

  @Test
  void matchType_preservedCorrectly() {
    assertEquals(ClassMatcher.MatchType.EXACT_NAME, ClassMatcher.byName("x").getMatchType());
    assertEquals(
        ClassMatcher.MatchType.NAME_PATTERN, ClassMatcher.byNamePattern("x").getMatchType());
    assertEquals(ClassMatcher.MatchType.ANNOTATION, ClassMatcher.byAnnotation("x").getMatchType());
    assertEquals(ClassMatcher.MatchType.SUPER_CLASS, ClassMatcher.bySuperClass("x").getMatchType());
    assertEquals(ClassMatcher.MatchType.INTERFACE, ClassMatcher.byInterface("x").getMatchType());
  }
}
