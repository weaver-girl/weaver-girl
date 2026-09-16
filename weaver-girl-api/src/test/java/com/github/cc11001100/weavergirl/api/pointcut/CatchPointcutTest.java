package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class CatchPointcutTest {

  private static Pointcut methodPointcut(String className, String methodName) {
    return new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName(methodName));
  }

  // --- constructor / getters ---

  @Test
  void constructor_setsMethodPointcutAndExceptionTypeName() {
    Pointcut mp = methodPointcut("com.example.Service", "process");
    CatchPointcut cp = new CatchPointcut(mp, "java.io.IOException");

    assertSame(mp, cp.getMethodPointcut());
    assertEquals("java.io.IOException", cp.getExceptionTypeName());
  }

  @Test
  void constructor_allowsNullExceptionTypeName() {
    Pointcut mp = methodPointcut("com.example.Service", "process");
    CatchPointcut cp = new CatchPointcut(mp, null);

    assertNull(cp.getExceptionTypeName());
  }

  // --- inClass factory ---

  @Test
  void inClass_buildsPointcutMatchingAnyMethodInClass() {
    CatchPointcut cp = CatchPointcut.inClass(ClassMatcher.byName("com.example.Service"), IOException.class);

    assertEquals("java.io.IOException", cp.getExceptionTypeName());
    assertTrue(cp.matches("com.example.Service", "anyMethodName", IOException.class));
    assertTrue(cp.matches("com.example.Service", "anotherMethod", IOException.class));
    assertFalse(cp.matches("com.example.Other", "anyMethodName", IOException.class));
  }

  // --- inMethod factory ---

  @Test
  void inMethod_buildsPointcutWithGivenMethodPointcutAndExceptionType() {
    Pointcut mp = methodPointcut("com.example.Service", "process");
    CatchPointcut cp = CatchPointcut.inMethod(mp, IOException.class);

    assertSame(mp, cp.getMethodPointcut());
    assertEquals("java.io.IOException", cp.getExceptionTypeName());
  }

  // --- matches: method pointcut gating ---

  @Test
  void matches_returnsFalse_whenMethodPointcutDoesNotMatch() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);

    assertFalse(cp.matches("com.example.Service", "otherMethod", IOException.class));
    assertFalse(cp.matches("com.example.Other", "process", IOException.class));
  }

  // --- matches: null exceptionTypeName means "any exception" ---

  @Test
  void matches_nullExceptionTypeName_matchesAnyExceptionType() {
    CatchPointcut cp = new CatchPointcut(methodPointcut("com.example.Service", "process"), null);

    assertTrue(cp.matches("com.example.Service", "process", IOException.class));
    assertTrue(cp.matches("com.example.Service", "process", RuntimeException.class));
  }

  @Test
  void matches_nullExceptionTypeName_matchesEvenWhenCaughtExceptionTypeIsNull() {
    CatchPointcut cp = new CatchPointcut(methodPointcut("com.example.Service", "process"), null);

    assertTrue(cp.matches("com.example.Service", "process", null));
  }

  // --- matches: exceptionTypeName set, caughtExceptionType null ---

  @Test
  void matches_returnsFalse_whenCaughtExceptionTypeIsNullButExceptionTypeNameIsSet() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);

    assertFalse(cp.matches("com.example.Service", "process", null));
  }

  // --- matches: exact exception type match ---

  @Test
  void matches_returnsTrue_forExactExceptionTypeMatch() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);

    assertTrue(cp.matches("com.example.Service", "process", IOException.class));
  }

  // --- matches: subclass matching (walk up superclass chain) ---

  @Test
  void matches_returnsTrue_whenCaughtExceptionIsSubclassOfDeclaredType() {
    // Declared type is IOException; caught exception is a subclass (UncheckedIOException wraps,
    // but FileNotFoundException/ EOFException are true IOException subclasses). Use a custom subclass.
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), Exception.class);

    assertTrue(cp.matches("com.example.Service", "process", IOException.class));
    assertTrue(cp.matches("com.example.Service", "process", RuntimeException.class));
  }

  @Test
  void matches_returnsFalse_whenCaughtExceptionIsSuperclassOfDeclaredType() {
    // Declared type is IOException (specific); caught is the broader Exception -> should not match,
    // since matching only walks UP from the caught type's own hierarchy, not down.
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);

    assertFalse(cp.matches("com.example.Service", "process", Exception.class));
    assertFalse(cp.matches("com.example.Service", "process", RuntimeException.class));
  }

  @Test
  void matches_returnsFalse_forUnrelatedExceptionType() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IllegalStateException.class);

    assertFalse(cp.matches("com.example.Service", "process", IOException.class));
  }

  @Test
  void matches_walksMultipleLevelsOfSuperclassHierarchy() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), RuntimeException.class);

    // UncheckedIOException -> RuntimeException -> Exception -> Throwable
    assertTrue(cp.matches("com.example.Service", "process", UncheckedIOException.class));
  }

  // --- equals / hashCode ---

  @Test
  void equals_reflexive() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);
    assertEquals(cp, cp);
  }

  @Test
  void equals_null_returnsFalse() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);
    assertNotEquals(null, cp);
  }

  @Test
  void equals_differentType_returnsFalse() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);
    assertNotEquals("not a CatchPointcut", cp);
  }

  @Test
  void equals_sameFields_returnsTrueAndSameHashCode() {
    Pointcut mp1 = methodPointcut("com.example.Service", "process");
    Pointcut mp2 = methodPointcut("com.example.Service", "process");
    CatchPointcut cp1 = new CatchPointcut(mp1, "java.io.IOException");
    CatchPointcut cp2 = new CatchPointcut(mp2, "java.io.IOException");

    assertEquals(cp1, cp2);
    assertEquals(cp1.hashCode(), cp2.hashCode());
  }

  @Test
  void equals_differentExceptionTypeName_returnsFalse() {
    Pointcut mp = methodPointcut("com.example.Service", "process");
    CatchPointcut cp1 = new CatchPointcut(mp, "java.io.IOException");
    CatchPointcut cp2 = new CatchPointcut(mp, "java.lang.RuntimeException");

    assertNotEquals(cp1, cp2);
  }

  @Test
  void equals_differentMethodPointcut_returnsFalse() {
    CatchPointcut cp1 =
        new CatchPointcut(methodPointcut("com.example.Service", "process"), "java.io.IOException");
    CatchPointcut cp2 =
        new CatchPointcut(methodPointcut("com.example.Other", "process"), "java.io.IOException");

    assertNotEquals(cp1, cp2);
  }

  @Test
  void equals_bothNullExceptionTypeName_returnsTrue() {
    Pointcut mp = methodPointcut("com.example.Service", "process");
    CatchPointcut cp1 = new CatchPointcut(mp, null);
    CatchPointcut cp2 = new CatchPointcut(mp, null);

    assertEquals(cp1, cp2);
  }

  // --- toString ---

  @Test
  void toString_containsMethodPointcutAndExceptionTypeName() {
    CatchPointcut cp = CatchPointcut.inMethod(methodPointcut("com.example.Service", "process"), IOException.class);

    String s = cp.toString();
    assertTrue(s.contains("CatchPointcut"));
    assertTrue(s.contains("java.io.IOException"));
  }
}
