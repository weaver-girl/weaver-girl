package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.Test;

class PointcutTest {

  @Test
  void pointcut_combinesClassAndMethodMatchers() {
    Pointcut pointcut =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    assertEquals("com.example.Service", pointcut.getClassMatcher().getPattern());
    assertEquals("process", pointcut.getMethodMatcher().getPattern());
  }

  @Test
  void instanceBindingKeepsTheMatchersAndMarksTheCopy() {
    Pointcut pointcut =
        new Pointcut(
            ClassMatcher.byName("com.example.Service"),
            MethodMatcher.byName("process"),
            PointcutExpression.execution("*", "com.example.Service", "process", ".."));

    assertNull(new Pointcut(ClassMatcher.any(), MethodMatcher.any()).getExpression());
    assertFalse(pointcut.isInstanceBound());
    assertSame(pointcut.getExpression(), pointcut.pertarget().getExpression());
    assertTrue(pointcut.pertarget().isInstanceBound());
    assertTrue(pointcut.perthis().isInstanceBound());
    assertTrue(pointcut.pertarget().matches("com.example.Service", "process"));
    assertFalse(pointcut.perthis().matches("com.example.Service", "other"));
  }

  @Test
  void interceptorDefinition_holdsAllFields() {
    Pointcut pointcut = new Pointcut(ClassMatcher.byName("svc"), MethodMatcher.any());
    Interceptor interceptor = new Interceptor() {};
    InterceptorDefinition def = new InterceptorDefinition("test", pointcut, interceptor, 5);
    assertEquals("test", def.getName());
    assertEquals(5, def.getPriority());
    assertNotNull(def.toString());
  }
}
