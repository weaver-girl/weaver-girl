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

  // --- Helpers ---

  @interface Traced {}
}
