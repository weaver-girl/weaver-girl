package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.Test;

class PointcutCompositionTest {

  @Test
  void and_bothMatch_returnsTrue() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    assertTrue(p1.and(p2).matches("com.example.Service", "process"));
  }

  @Test
  void and_oneFails_returnsFalse() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Other"), MethodMatcher.byName("run"));
    assertFalse(p1.and(p2).matches("com.example.Service", "process"));
  }

  @Test
  void or_eitherMatches_returnsTrue() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Other"), MethodMatcher.byName("run"));
    assertTrue(p1.or(p2).matches("com.example.Service", "process"));
    assertTrue(p1.or(p2).matches("com.example.Other", "run"));
  }

  @Test
  void or_neitherMatches_returnsFalse() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Other"), MethodMatcher.byName("run"));
    assertFalse(p1.or(p2).matches("com.example.Unknown", "save"));
  }

  @Test
  void negate_invertsMatch() {
    Pointcut p = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    assertTrue(p.negate().matches("com.example.Other", "run"));
    assertFalse(p.negate().matches("com.example.Service", "process"));
  }

  @Test
  void composedPointcuts_preserveMatcherTypes() {
    Pointcut anyMethod = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.any());
    Pointcut specific = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));

    Pointcut combined = anyMethod.and(specific);
    assertEquals(MethodMatcher.MatchType.EXACT_NAME, combined.getMethodMatcher().getMatchType());
  }
}
