package com.github.cc11001100.weavergirl.api.matcher;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FieldMatcherTest {

  @Test
  void byName_exactMatch() {
    FieldMatcher m = FieldMatcher.byName("password");
    assertEquals(FieldMatcher.MatchType.EXACT_NAME, m.getMatchType());
    assertEquals("password", m.getPattern());
    assertTrue(m.matches("password", "java.lang.String"));
    assertFalse(m.matches("username", "java.lang.String"));
  }

  @Test
  void byName_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byName(null));
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byName(""));
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byName("   "));
  }

  @Test
  void byNamePattern_regexMatch() {
    FieldMatcher m = FieldMatcher.byNamePattern(".*[Pp]assword.*");
    assertEquals(FieldMatcher.MatchType.NAME_PATTERN, m.getMatchType());
    assertTrue(m.matches("password", null));
    assertTrue(m.matches("userPasswordHash", null));
    assertFalse(m.matches("username", null));
  }

  @Test
  void byNamePattern_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byNamePattern(null));
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byNamePattern(""));
  }

  @Test
  void byType_matchesTypeNameOnly() {
    FieldMatcher m = FieldMatcher.byType("java.lang.String");
    assertEquals(FieldMatcher.MatchType.TYPE, m.getMatchType());
    assertTrue(m.matches("anyFieldName", "java.lang.String"));
    assertFalse(m.matches("anyFieldName", "java.lang.Integer"));
    assertFalse(m.matches("anyFieldName", null));
  }

  @Test
  void byType_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byType(null));
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byType(""));
  }

  @Test
  void byAnnotation_neverMatchesFromStringsOnly() {
    FieldMatcher m = FieldMatcher.byAnnotation("com.example.Sensitive");
    assertEquals(FieldMatcher.MatchType.ANNOTATION, m.getMatchType());
    assertEquals("com.example.Sensitive", m.getPattern());
    assertFalse(m.matches("password", "java.lang.String"));
    assertFalse(m.matches("anything", null));
  }

  @Test
  void byAnnotation_nullOrEmpty_throws() {
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byAnnotation(null));
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.byAnnotation(""));
  }

  @Test
  void get_matchesAnyFieldNameAndType() {
    FieldMatcher m = FieldMatcher.get();
    assertEquals(FieldMatcher.MatchType.GET, m.getMatchType());
    assertEquals("get", m.getPattern());
    assertTrue(m.matches("anything", null));
    assertTrue(m.matches("", "java.lang.Object"));
  }

  @Test
  void set_matchesAnyFieldNameAndType() {
    FieldMatcher m = FieldMatcher.set();
    assertEquals(FieldMatcher.MatchType.SET, m.getMatchType());
    assertEquals("set", m.getPattern());
    assertTrue(m.matches("anything", null));
  }

  @Test
  void any_matchesAll() {
    FieldMatcher m = FieldMatcher.any();
    assertEquals(FieldMatcher.MatchType.ANY, m.getMatchType());
    assertEquals("*", m.getPattern());
    assertTrue(m.matches("anything", "anything"));
    assertTrue(m.matches(null, null));
  }

  @Test
  void equalsAndHashCode() {
    FieldMatcher a = FieldMatcher.byName("password");
    FieldMatcher b = FieldMatcher.byName("password");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());

    FieldMatcher c = FieldMatcher.byName("username");
    assertNotEquals(a, c);

    FieldMatcher d = FieldMatcher.byType("password");
    assertNotEquals(a, d, "Same pattern string but different match type must not be equal");

    assertNotEquals(a, null);
    assertNotEquals(a, "password");
    assertEquals(a, a);
  }

  @Test
  void toString_containsMatchTypeAndPattern() {
    FieldMatcher m = FieldMatcher.byName("password");
    String s = m.toString();
    assertTrue(s.contains("EXACT_NAME"));
    assertTrue(s.contains("password"));
  }
}
