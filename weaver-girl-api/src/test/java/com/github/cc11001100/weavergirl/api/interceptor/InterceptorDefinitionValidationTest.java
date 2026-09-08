package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import org.junit.jupiter.api.Test;

class InterceptorDefinitionValidationTest {

  private static final Pointcut VALID_POINTCUT =
      new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
  private static final Interceptor VALID_INTERCEPTOR = new Interceptor() {};

  @Test
  void constructor_nullName_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InterceptorDefinition(null, VALID_POINTCUT, VALID_INTERCEPTOR));
  }

  @Test
  void constructor_emptyName_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InterceptorDefinition("", VALID_POINTCUT, VALID_INTERCEPTOR));
  }

  @Test
  void constructor_nullPointcut_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InterceptorDefinition("test", null, VALID_INTERCEPTOR));
  }

  @Test
  void constructor_nullInterceptor_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InterceptorDefinition("test", VALID_POINTCUT, null));
  }

  @Test
  void constructor_validArgs_succeeds() {
    InterceptorDefinition def =
        new InterceptorDefinition("test", VALID_POINTCUT, VALID_INTERCEPTOR);
    assertEquals("test", def.getName());
    assertEquals(VALID_POINTCUT, def.getPointcut());
    assertEquals(VALID_INTERCEPTOR, def.getInterceptor());
    assertEquals(0, def.getPriority());
  }

  @Test
  void constructor_validArgsWithPriority_succeeds() {
    InterceptorDefinition def =
        new InterceptorDefinition("test", VALID_POINTCUT, VALID_INTERCEPTOR, 42);
    assertEquals("test", def.getName());
    assertEquals(42, def.getPriority());
  }
}
