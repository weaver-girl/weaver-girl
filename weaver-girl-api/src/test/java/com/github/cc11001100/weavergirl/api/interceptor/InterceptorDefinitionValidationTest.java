package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
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

  @Test
  void everyConstructorDelegatesAndANullAdviceModeIsRejected() {
    InterceptorDefinition withMode =
        new InterceptorDefinition(
            "mode", VALID_POINTCUT, VALID_INTERCEPTOR, 1, InterceptorDefinition.AdviceMode.FIELD);
    InterceptorDefinition withAround =
        new InterceptorDefinition(
            "around",
            VALID_POINTCUT,
            VALID_INTERCEPTOR,
            2,
            InterceptorDefinition.AdviceMode.STANDARD,
            true);
    InterceptorDefinition withMatchers =
        new InterceptorDefinition(
            "matchers",
            VALID_POINTCUT,
            VALID_INTERCEPTOR,
            3,
            InterceptorDefinition.AdviceMode.CATCH,
            false,
            FieldMatcher.byName("balance"),
            "java.io.IOException");

    assertEquals(InterceptorDefinition.AdviceMode.FIELD, withMode.getAdviceMode());
    assertEquals(InterceptorDefinition.AdviceMode.STANDARD, withAround.getAdviceMode());
    assertEquals(InterceptorDefinition.AdviceMode.CATCH, withMatchers.getAdviceMode());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InterceptorDefinition(
                "broken", VALID_POINTCUT, VALID_INTERCEPTOR, 0, null, false));
  }

  @Test
  void aspectClassNamePrefersTheExplicitNameThenTheAnnotationPrefix() {
    InterceptorDefinition explicit =
        new InterceptorDefinition(
            "annotation-Ignored-process",
            VALID_POINTCUT,
            VALID_INTERCEPTOR,
            0,
            InterceptorDefinition.AdviceMode.STANDARD,
            false,
            null,
            null,
            "com.example.SecurityAspect");
    InterceptorDefinition inferred =
        new InterceptorDefinition("annotation-LoggingAspect-process", VALID_POINTCUT, VALID_INTERCEPTOR);
    InterceptorDefinition barePrefix =
        new InterceptorDefinition("annotation-", VALID_POINTCUT, VALID_INTERCEPTOR);

    assertEquals("com.example.SecurityAspect", explicit.getAspectClassName());
    assertEquals("LoggingAspect", inferred.getAspectClassName());
    assertEquals("annotation-", barePrefix.getAspectClassName());
    assertEquals("plain", new InterceptorDefinition("plain", VALID_POINTCUT, VALID_INTERCEPTOR).getAspectClassName());
  }

  @Test
  void ownerIsRecordedSeparatelyFromEquality() {
    InterceptorDefinition left =
        new InterceptorDefinition("same", VALID_POINTCUT, VALID_INTERCEPTOR, 4);
    InterceptorDefinition right =
        new InterceptorDefinition(
            "same", VALID_POINTCUT, VALID_INTERCEPTOR, 4, InterceptorDefinition.AdviceMode.STANDARD);
    Object owner = new Object();

    assertNull(left.getOwner());
    left.setOwner(owner);

    assertSame(owner, left.getOwner());
    assertEquals(left, right);
    assertEquals(left.hashCode(), right.hashCode());
    assertEquals(left, left);
    assertNotEquals(left, "same");
    assertNotEquals(left, new InterceptorDefinition("other", VALID_POINTCUT, VALID_INTERCEPTOR, 4));
    assertTrue(left.toString().contains("same"));
    assertTrue(left.toString().contains("STANDARD"));
  }
}
