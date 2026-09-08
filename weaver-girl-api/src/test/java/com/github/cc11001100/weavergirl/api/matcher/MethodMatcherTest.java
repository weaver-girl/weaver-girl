package com.github.cc11001100.weavergirl.api.matcher;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MethodMatcherTest {

  @Test
  void byName_matchesExact() {
    MethodMatcher matcher = MethodMatcher.byName("greet");
    assertTrue(matcher.matches("greet"));
  }

  @Test
  void byName_doesNotMatchDifferent() {
    MethodMatcher matcher = MethodMatcher.byName("greet");
    assertFalse(matcher.matches("hello"));
  }

  @Test
  void byName_returnsTrueForExactMatch() {
    assertTrue(MethodMatcher.byName("greet").matches("greet"));
  }

  @Test
  void byName_returnsFalseForNonMatch() {
    assertFalse(MethodMatcher.byName("greet").matches("hello"));
  }

  @Test
  void any_matchesEverything() {
    MethodMatcher matcher = MethodMatcher.any();
    assertTrue(matcher.matches("greet"));
    assertTrue(matcher.matches("hello"));
    assertTrue(matcher.matches("anything"));
    assertTrue(matcher.matches(""));
  }

  @Test
  void byNamePattern_matchesPattern() {
    MethodMatcher matcher = MethodMatcher.byNamePattern("get.*");
    assertTrue(matcher.matches("getName"));
    assertTrue(matcher.matches("getAge"));
    assertFalse(matcher.matches("setAge"));
  }

  @Test
  void byNamePattern_matchesDigits() {
    MethodMatcher matcher = MethodMatcher.byNamePattern("process\\d+");
    assertTrue(matcher.matches("process1"));
    assertTrue(matcher.matches("process2"));
    assertFalse(matcher.matches("process"));
  }

  @Test
  void byAnnotation_hasCorrectMatchType() {
    MethodMatcher matcher = MethodMatcher.byAnnotation("com.example.MyAnnotation");
    assertEquals(MethodMatcher.MatchType.ANNOTATION, matcher.getMatchType());
  }

  @Test
  void byName_hasCorrectMatchType() {
    assertEquals(MethodMatcher.MatchType.EXACT_NAME, MethodMatcher.byName("foo").getMatchType());
  }

  @Test
  void byNamePattern_hasCorrectMatchType() {
    assertEquals(
        MethodMatcher.MatchType.NAME_PATTERN, MethodMatcher.byNamePattern(".*").getMatchType());
  }

  @Test
  void any_hasCorrectMatchType() {
    assertEquals(MethodMatcher.MatchType.ANY, MethodMatcher.any().getMatchType());
  }

  @Test
  void getPattern_returnsCorrectPattern() {
    assertEquals("greet", MethodMatcher.byName("greet").getPattern());
    assertEquals("get.*", MethodMatcher.byNamePattern("get.*").getPattern());
    assertEquals(
        "com.example.MyAnnotation",
        MethodMatcher.byAnnotation("com.example.MyAnnotation").getPattern());
    assertEquals("*", MethodMatcher.any().getPattern());
  }

  @Test
  void signatureAndConstructorMatchersCheckParameters() {
    MethodMatcher signature = MethodMatcher.bySignature("work", "java.lang.String,int");
    assertEquals(MethodMatcher.MatchType.SIGNATURE, signature.getMatchType());
    assertEquals("work(java.lang.String,int)", signature.getPattern());
    assertTrue(signature.matches("work"));
    assertFalse(signature.matches("other"));
    assertTrue(signature.matches("work", new Class<?>[] {String.class, int.class}));
    assertFalse(signature.matches("work", new Class<?>[] {String.class}));
    assertFalse(signature.matches("work", new Class<?>[] {String.class, long.class}));
    assertFalse(signature.matches("work", null));

    MethodMatcher noArg = MethodMatcher.bySignature("work", "");
    assertTrue(noArg.matches("work", null));
    assertTrue(noArg.matches("work", new Class<?>[0]));
    assertFalse(noArg.matches("work", new Class<?>[] {String.class}));

    MethodMatcher constructor = MethodMatcher.byConstructor();
    assertEquals(MethodMatcher.MatchType.CONSTRUCTOR, constructor.getMatchType());
    assertTrue(constructor.matches("<init>"));
    assertTrue(constructor.matches("<init>", new Class<?>[] {String.class}));
    MethodMatcher typedConstructor = MethodMatcher.byConstructor("java.lang.String");
    assertTrue(typedConstructor.matches("<init>", new Class<?>[] {String.class}));
    assertFalse(typedConstructor.matches("<init>", new Class<?>[0]));
  }

  @Test
  void annotationAndEqualityBehaviorIsStable() {
    MethodMatcher annotation = MethodMatcher.byAnnotation("pkg.Marked");
    assertTrue(annotation.matches("anything"));
    assertTrue(annotation.matches("anything", new Class<?>[] {String.class}));
    assertEquals(annotation, MethodMatcher.byAnnotation("pkg.Marked"));
    assertEquals(annotation.hashCode(), MethodMatcher.byAnnotation("pkg.Marked").hashCode());
    assertNotEquals(annotation, MethodMatcher.byName("pkg.Marked"));
    assertNotEquals(annotation, null);
    assertNotEquals(annotation, "annotation");
    assertTrue(annotation.toString().contains("ANNOTATION"));
  }

  @Test
  void factoriesRejectMissingValues() {
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byName(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byName(""));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byNamePattern(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byAnnotation(""));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.bySignature(null, ""));
    assertThrows(NullPointerException.class, () -> MethodMatcher.bySignature("x", null));
    assertThrows(NullPointerException.class, () -> MethodMatcher.byConstructor(null));
  }
}
