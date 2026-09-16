package com.github.cc11001100.weavergirl.api.matcher;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class MethodMatcherTest {

  @Test
  void byName_exactMatch() {
    MethodMatcher m = MethodMatcher.byName("process");
    assertTrue(m.matches("process"));
    assertFalse(m.matches("processData"));
    assertFalse(m.matches("pro"));
  }

  @Test
  void byNamePattern_regexMatch() {
    MethodMatcher m = MethodMatcher.byNamePattern("process.*");
    assertTrue(m.matches("process"));
    assertTrue(m.matches("processData"));
    assertFalse(m.matches("run"));
  }

  @Test
  void byAnnotation_alwaysTrueWhenAlreadySelected() {
    MethodMatcher m = MethodMatcher.byAnnotation("com.example.Traced");
    assertTrue(m.matches("anything"));
  }

  @Test
  void bySignature_nameAndParamMatch() throws Exception {
    MethodMatcher m = MethodMatcher.bySignature("process", "java.lang.String,int");
    assertTrue(m.matches("process", new Class[]{String.class, int.class}));
    assertFalse(m.matches("process", new Class[]{String.class}));
    assertFalse(m.matches("run", new Class[]{String.class, int.class}));
  }

  @Test
  void byConstructor_matchesInit() throws Exception {
    MethodMatcher c = MethodMatcher.byConstructor();
    assertTrue(c.matches("<init>"));
    assertFalse(c.matches("toString"));
  }

  @Test
  void byConstructor_withParams_matchesSignature() throws Exception {
    MethodMatcher c = MethodMatcher.byConstructor("java.lang.String,int");
    assertTrue(c.matches("<init>", new Class[]{String.class, int.class}));
    assertFalse(c.matches("<init>", new Class[]{String.class}));
  }

  @Test
  void byArgumentAnnotation_matchesIndexedParamTypes() throws Exception {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(Traced.class.getName(), 0, 2);
    assertTrue(m.matches("run", new Class[]{Traced.class, Object.class, Traced.class}));
    assertFalse(m.matches("run", new Class[]{Object.class, Traced.class}));
  }

  @Test
  void any_matchesAll() {
    MethodMatcher m = MethodMatcher.any();
    assertTrue(m.matches("anything"));
    assertTrue(m.matches(""));
  }

  @Test
  void fieldAccess_matchers() {
    MethodMatcher get = MethodMatcher.byFieldGet("value");
    assertTrue(get.matches("value"));
    assertTrue(get.matches("getvalue"));
    assertTrue(get.matches("access$value"));

    MethodMatcher set = MethodMatcher.byFieldSet("value");
    assertTrue(set.matches("value"));
    assertTrue(set.matches("setvalue"));
    assertTrue(set.matches("access$setvalue"));
  }

  @Test
  void equalsAndHashCode() {
    MethodMatcher a = MethodMatcher.byName("run");
    MethodMatcher b = MethodMatcher.byName("run");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());

    MethodMatcher c = MethodMatcher.byName("process");
    assertNotEquals(a, c);
  }

  @Test
  void toString_containsTypeAndPattern() {
    MethodMatcher m = MethodMatcher.byNamePattern("run.*");
    assertTrue(m.toString().contains("NAME_PATTERN"));
    assertTrue(m.toString().contains("run.*"));
  }

  @Test
  void getMatchType_andGetPattern() {
    MethodMatcher m = MethodMatcher.byName("process");
    assertEquals(MethodMatcher.MatchType.EXACT_NAME, m.getMatchType());
    assertEquals("process", m.getPattern());
  }

  @Test
  void getArgumentAnnotationClassNameAndIndexes_nullForNonArgsMatcher() {
    MethodMatcher m = MethodMatcher.byName("process");
    assertNull(m.getArgumentAnnotationClassName());
    assertNull(m.getArgumentIndexes());
  }

  @Test
  void getArgumentAnnotationClassNameAndIndexes_forArgsMatcher() {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(Traced.class.getName(), 0, 2);
    assertEquals(Traced.class.getName(), m.getArgumentAnnotationClassName());
    assertArrayEquals(new int[] {0, 2}, m.getArgumentIndexes());
  }

  // --- Validation failures ---

  @Test
  void byName_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byName(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byName(""));
  }

  @Test
  void byNamePattern_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byNamePattern(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byNamePattern(""));
  }

  @Test
  void byAnnotation_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byAnnotation(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byAnnotation(""));
  }

  @Test
  void bySignature_nullMethodNameOrParameterTypes_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> MethodMatcher.bySignature(null, "java.lang.String"));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.bySignature("", "java.lang.String"));
    // parameterTypes is validated via ValidationUtils.requireNonNull, which delegates to
    // Objects.requireNonNull and therefore throws NullPointerException, not IllegalArgumentException.
    assertThrows(NullPointerException.class, () -> MethodMatcher.bySignature("process", null));
  }

  @Test
  void byConstructor_withNullParameterTypes_throws() {
    assertThrows(NullPointerException.class, () -> MethodMatcher.byConstructor(null));
  }

  @Test
  void byFieldGet_andByFieldSet_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byFieldGet(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byFieldGet(""));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byFieldSet(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byFieldSet(""));
  }

  @Test
  void byArgumentAnnotation_nullOrEmptyAnnotationClassName_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> MethodMatcher.byArgumentAnnotation(null, 0));
    assertThrows(
        IllegalArgumentException.class, () -> MethodMatcher.byArgumentAnnotation("", 0));
  }

  @Test
  void byArgumentAnnotation_nullOrEmptyIndexes_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MethodMatcher.byArgumentAnnotation(Traced.class.getName(), (int[]) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> MethodMatcher.byArgumentAnnotation(Traced.class.getName()));
  }

  // --- byConstructor() with no parameter types matches any signature ---

  @Test
  void byConstructor_noParameterTypes_matchesAnySignature() {
    MethodMatcher c = MethodMatcher.byConstructor();
    assertTrue(c.matches("<init>", new Class[] {String.class, int.class}));
    assertTrue(c.matches("<init>", new Class[0]));
    assertFalse(c.matches("other", new Class[0]));
  }

  @Test
  void byConstructor_emptyParameterTypes_matchesNoArgConstructor() {
    MethodMatcher c = MethodMatcher.byConstructor("");
    assertTrue(c.matches("<init>", new Class[0]));
    assertTrue(c.matches("<init>", (Class<?>[]) null));
    assertFalse(c.matches("<init>", new Class[] {String.class}));
  }

  @Test
  void bySignature_matchesTwoArg_nullParameterTypes_returnsFalse() {
    MethodMatcher m = MethodMatcher.bySignature("process", "java.lang.String");
    assertFalse(m.matches("process", null));
  }

  @Test
  void bySignature_matchesTwoArg_typeMismatchAtSameLength_returnsFalse() {
    MethodMatcher m = MethodMatcher.bySignature("process", "java.lang.String,int");
    assertFalse(m.matches("process", new Class[] {Integer.class, int.class}));
  }

  @Test
  void nonSignatureMatcher_matchesTwoArg_delegatesToSingleArgMatches() {
    MethodMatcher m = MethodMatcher.byName("process");
    assertTrue(m.matches("process", new Class[] {String.class}));
    assertFalse(m.matches("other", new Class[] {String.class}));
  }

  // --- CFIELD_GET / CFIELD_SET non-matching branches ---

  @Test
  void fieldGet_nonMatchingName_returnsFalse() {
    MethodMatcher get = MethodMatcher.byFieldGet("value");
    assertFalse(get.matches("other"));
    assertFalse(get.matches("setvalue"));
    assertFalse(get.matches(null));
    assertFalse(get.matches(""));
  }

  @Test
  void fieldSet_nonMatchingName_returnsFalse() {
    MethodMatcher set = MethodMatcher.byFieldSet("value");
    assertFalse(set.matches("other"));
    assertFalse(set.matches("getvalue"));
    assertFalse(set.matches(null));
    assertFalse(set.matches(""));
  }

  // --- ARGS matching edge cases ---

  @Test
  void byArgumentAnnotation_matches_delegatesFromSingleArgOverload() {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(Traced.class.getName(), 0);
    assertFalse(m.matches("run"));
  }

  @Test
  void byArgumentAnnotation_nullOrEmptyParameterTypes_returnsFalse() {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(Traced.class.getName(), 0);
    assertFalse(m.matches("run", null));
    assertFalse(m.matches("run", new Class[0]));
  }

  @Test
  void byArgumentAnnotation_indexOutOfBounds_returnsFalse() {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(Traced.class.getName(), 5);
    assertFalse(m.matches("run", new Class[] {Traced.class}));
  }

  @Test
  void byArgumentAnnotation_negativeIndex_returnsFalse() {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(Traced.class.getName(), -1);
    assertFalse(m.matches("run", new Class[] {Traced.class}));
  }

  @Test
  void byArgumentAnnotation_matchesBySimpleNameAcrossDistinctClasses() throws Exception {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(MarkerAnnotation.class.getName(), 0);
    // OtherHolder.MarkerAnnotation is a distinct class from MarkerAnnotation with the same
    // simple name; matching falls through to the simple-name comparison.
    assertTrue(m.matches("run", new Class[] {OtherHolder.MarkerAnnotation.class}));
  }

  @Test
  void byArgumentAnnotation_unresolvableAnnotationClass_returnsFalse() {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation("com.example.DoesNotExist", 0);
    assertFalse(m.matches("run", new Class[] {Traced.class}));
  }

  @Test
  void byArgumentAnnotation_realAnnotationPresentOnParameterType_matches() throws Exception {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(MarkerAnnotation.class.getName(), 0);
    assertTrue(m.matches("run", new Class[] {AnnotatedTarget.class}));
    assertFalse(m.matches("run", new Class[] {Object.class}));
  }

  // --- SIGNATURE matches(String) name-only branch ---

  @Test
  void bySignature_matchesStringOnly_checksNameOnly() {
    MethodMatcher m = MethodMatcher.bySignature("process", "java.lang.String");
    assertTrue(m.matches("process"));
    assertFalse(m.matches("run"));
  }

  // --- default branch: ARGS not handled by matches(String) ---

  @Test
  void argsMatcher_matchesStringOnly_returnsFalse() {
    MethodMatcher m = MethodMatcher.byArgumentAnnotation(Traced.class.getName(), 0);
    assertFalse(m.matches("run"));
  }

  // --- equals edge cases ---

  @Test
  void equals_sameInstance_true() {
    MethodMatcher m = MethodMatcher.byName("run");
    assertEquals(m, m);
  }

  @Test
  void equals_differentType_false() {
    MethodMatcher m = MethodMatcher.byName("run");
    assertNotEquals(m, "run");
    assertNotEquals(m, null);
  }

  @Test
  void equals_samePatternDifferentMatchType_false() {
    MethodMatcher constructor = MethodMatcher.byConstructor();
    MethodMatcher byName = MethodMatcher.byName("<init>");
    assertNotEquals(constructor, byName);
  }

  // --- Helpers ---

  @interface Traced {}

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @interface MarkerAnnotation {}

  @MarkerAnnotation
  static class AnnotatedTarget {}

  static class OtherHolder {
    @interface MarkerAnnotation {}
  }
}
