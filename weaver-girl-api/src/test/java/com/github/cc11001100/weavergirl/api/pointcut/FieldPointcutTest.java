package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.Test;

class FieldPointcutTest {

  @Test
  void constructor_storesComponents() {
    Pointcut methodPointcut =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.any());
    FieldMatcher fieldMatcher = FieldMatcher.byName("password");

    FieldPointcut fp = new FieldPointcut(methodPointcut, fieldMatcher);

    assertSame(methodPointcut, fp.getMethodPointcut());
    assertSame(fieldMatcher, fp.getFieldMatcher());
  }

  @Test
  void inClass_appliesToAllMethodsInMatchedClass() {
    ClassMatcher classMatcher = ClassMatcher.byName("com.example.Service");
    FieldMatcher fieldMatcher = FieldMatcher.byName("password");

    FieldPointcut fp = FieldPointcut.inClass(classMatcher, fieldMatcher);

    assertEquals(classMatcher, fp.getMethodPointcut().getClassMatcher());
    assertEquals(MethodMatcher.MatchType.ANY, fp.getMethodPointcut().getMethodMatcher().getMatchType());
    assertSame(fieldMatcher, fp.getFieldMatcher());
  }

  @Test
  void byField_appliesToAnyClass() {
    FieldMatcher fieldMatcher = FieldMatcher.byName("password");

    FieldPointcut fp = FieldPointcut.byField(fieldMatcher);

    assertEquals(ClassMatcher.MatchType.ANY, fp.getMethodPointcut().getClassMatcher().getMatchType());
    assertSame(fieldMatcher, fp.getFieldMatcher());
  }

  @Test
  void matches_requiresBothMethodPointcutAndFieldMatcherToMatch() {
    FieldPointcut fp =
        FieldPointcut.inClass(ClassMatcher.byName("com.example.Service"), FieldMatcher.byName("password"));

    assertTrue(fp.matches("com.example.Service", "anyMethod", "password", "java.lang.String"));
    assertFalse(
        fp.matches("com.example.Other", "anyMethod", "password", "java.lang.String"),
        "Class mismatch should fail overall match");
    assertFalse(
        fp.matches("com.example.Service", "anyMethod", "username", "java.lang.String"),
        "Field mismatch should fail overall match");
  }

  @Test
  void matches_byFieldAnyClass() {
    FieldPointcut fp = FieldPointcut.byField(FieldMatcher.byType("java.lang.String"));

    assertTrue(fp.matches("com.example.Anything", null, "name", "java.lang.String"));
    assertFalse(fp.matches("com.example.Anything", null, "count", "int"));
  }

  @Test
  void equalsAndHashCode() {
    FieldPointcut a = FieldPointcut.byField(FieldMatcher.byName("password"));
    FieldPointcut b = FieldPointcut.byField(FieldMatcher.byName("password"));
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());

    FieldPointcut c = FieldPointcut.byField(FieldMatcher.byName("username"));
    assertNotEquals(a, c);

    assertNotEquals(a, null);
    assertNotEquals(a, "not-a-pointcut");
    assertEquals(a, a);
  }

  @Test
  void toString_containsMethodPointcutAndFieldMatcher() {
    FieldPointcut fp = FieldPointcut.byField(FieldMatcher.byName("password"));
    String s = fp.toString();
    assertTrue(s.contains("FieldPointcut"));
    assertTrue(s.contains("password"));
  }
}
