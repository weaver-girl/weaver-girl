package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.AfterReturning;
import com.github.cc11001100.weavergirl.annotation.AfterThrowing;
import com.github.cc11001100.weavergirl.annotation.Around;
import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.EnableIf;
import com.github.cc11001100.weavergirl.annotation.OnCatch;
import com.github.cc11001100.weavergirl.annotation.OnException;
import com.github.cc11001100.weavergirl.annotation.OnFieldGet;
import com.github.cc11001100.weavergirl.annotation.OnFieldSet;
import com.github.cc11001100.weavergirl.annotation.SampleRate;
import com.github.cc11001100.weavergirl.annotation.Timed;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Structural and miscellaneous branch coverage for {@link AnnotationPluginLoader}, deliberately
 * scoped away from the per-annotation "wrapWithX" dimension wrappers (covered by sibling coverage
 * test classes). Focuses on: the outer catch-and-continue loop, {@code loadFromAnnotatedClass}'s
 * early-return guards, {@code checkEnableIfCondition}'s branches, named-pointcut resolution via
 * {@code resolvePointcutKey}, {@code buildClassMatcherFromAnnotation}'s five target-attribute
 * branches, field/catch reflective-invocation error paths, the {@code @WeaveClass(pointcut=...)}
 * early-return, and {@code recordTiming}.
 */
class StructuralAndMiscCoverageTest {

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
  }

  @AfterEach
  void tearDown() {
    System.clearProperty("weavergirl.test.enableFlag");
  }

  private void load(Class<?>... classes) {
    Set<Class<?>> set = new HashSet<>();
    for (Class<?> c : classes) {
      set.add(c);
    }
    loader.loadAnnotatedInterceptors(set, registry);
  }

  private InterceptorDefinition definitionEndingWith(String suffix) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith(suffix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition ending with " + suffix));
  }

  // ==================== Item 1: loadAnnotatedInterceptors catch-and-continue ====================

  @WeaveClass(target = "com.example.BrokenPointcutTarget")
  public static class BrokenPointcutClass {
    // "not-a-valid-pointcut!!!" is parsed eagerly while collecting named @Pointcut definitions
    // (inside loadFromAnnotatedClass, before any per-method dispatch). PointcutParser throws an
    // uncaught IllegalArgumentException here that is NOT caught inside loadFromAnnotatedClass
    // itself -- it propagates up to loadAnnotatedInterceptors's try/catch.
    @com.github.cc11001100.weavergirl.annotation.Pointcut("not-a-valid-pointcut!!!")
    void namedPointcut() {}

    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @WeaveClass(target = "com.example.GoodTarget")
  public static class GoodClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void loadAnnotatedInterceptors_catchesPerClassFailure_andContinuesLoadingOtherClasses() {
    // Deviation from the literal "constructor throws" example: a constructor-instantiation
    // failure is caught INTERNALLY by loadFromAnnotatedClass (lines 114-120) and never
    // propagates, so it cannot exercise loadAnnotatedInterceptors's own catch block. A malformed
    // pointcut expression string does propagate (PointcutParser.parse throws, uncaught within
    // loadFromAnnotatedClass), so it is used here instead to reach the intended outer catch.
    assertDoesNotThrow(() -> load(BrokenPointcutClass.class, GoodClass.class));

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size(), "the broken class must not prevent the good class from loading");
    assertTrue(defs.get(0).getName().contains("GoodClass"));
  }

  // ==================== Item 2: loadFromAnnotatedClass early returns ====================

  public static class NoWeaveClassAnnotationClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void loadFromAnnotatedClass_withoutWeaveClassAnnotation_skipsRegistration() {
    assertDoesNotThrow(() -> load(NoWeaveClassAnnotationClass.class));
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  @WeaveClass(target = "com.example.EnableIfDisabledTarget")
  @EnableIf(property = "weavergirl.test.enableFlag", havingValue = "on")
  public static class EnableIfDisabledByDefaultClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void loadFromAnnotatedClass_disabledByEnableIf_skipsRegistration() {
    // Property is intentionally left unset; matchIfMissing defaults to false, so the class is
    // disabled and loadFromAnnotatedClass returns early (before instantiation/registration).
    System.clearProperty("weavergirl.test.enableFlag");
    assertDoesNotThrow(() -> load(EnableIfDisabledByDefaultClass.class));
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  @WeaveClass(target = "com.example.SampleRateZeroTarget")
  @SampleRate(0.0)
  public static class SampleRateZeroClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void loadFromAnnotatedClass_disabledBySampleRateZero_skipsRegistration() {
    assertDoesNotThrow(() -> load(SampleRateZeroClass.class));
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  @WeaveClass(target = "com.example.ThrowingCtorTarget")
  public static class ThrowingConstructorClass {
    public ThrowingConstructorClass() {
      throw new RuntimeException("constructor deliberately fails for coverage");
    }

    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void loadFromAnnotatedClass_constructorInstantiationFailure_skipsRegistrationWithoutPropagating() {
    assertDoesNotThrow(() -> load(ThrowingConstructorClass.class));
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  // ==================== Item 3: checkEnableIfCondition branches ====================

  @WeaveClass(target = "com.example.NoEnableIfTarget")
  public static class NoEnableIfAnnotationClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void checkEnableIfCondition_noAnnotationPresent_defaultsEnabled() {
    load(NoEnableIfAnnotationClass.class);
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @WeaveClass(target = "com.example.PropertyMatchTarget")
  @EnableIf(property = "weavergirl.test.enableFlag", havingValue = "on")
  public static class PropertyMatchClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void checkEnableIfCondition_propertySetAndMatchesHavingValue_enables() {
    System.setProperty("weavergirl.test.enableFlag", "on");
    try {
      load(PropertyMatchClass.class);
      assertEquals(1, registry.getAllDefinitions().size());
    } finally {
      System.clearProperty("weavergirl.test.enableFlag");
    }
  }

  @Test
  void checkEnableIfCondition_propertySetButMismatchesHavingValue_disables() {
    System.setProperty("weavergirl.test.enableFlag", "off");
    try {
      load(PropertyMatchClass.class);
      assertTrue(registry.getAllDefinitions().isEmpty());
    } finally {
      System.clearProperty("weavergirl.test.enableFlag");
    }
  }

  @Test
  void checkEnableIfCondition_propertyNotSet_fallsThroughToMatchIfMissingFalse() {
    System.clearProperty("weavergirl.test.enableFlag");
    load(PropertyMatchClass.class);
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  @WeaveClass(target = "com.example.MatchIfMissingTrueTarget")
  @EnableIf(
      property = "weavergirl.test.enableFlag",
      havingValue = "on",
      matchIfMissing = true)
  public static class MatchIfMissingTrueClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void checkEnableIfCondition_propertyNotSet_matchIfMissingTrue_enables() {
    System.clearProperty("weavergirl.test.enableFlag");
    load(MatchIfMissingTrueClass.class);
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @WeaveClass(target = "com.example.EnvFallthroughTarget")
  @EnableIf(
      env = "WEAVERGIRL_TEST_DEFINITELY_UNSET_ENV_VAR_XYZ",
      havingValue = "on",
      matchIfMissing = true)
  public static class EnvUnsetFallsThroughToMatchIfMissingClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void checkEnableIfCondition_envNotSet_fallsThroughToMatchIfMissing() {
    // Environment variables cannot be reliably set from within a running JVM, so this only
    // exercises the "env var not present -> fall through" path (envValue == null), not the
    // "env var present and equals/mismatches havingValue" path. That path is covered below using
    // PATH, an env var that is virtually guaranteed to exist in any test environment.
    assertNull(
        System.getenv("WEAVERGIRL_TEST_DEFINITELY_UNSET_ENV_VAR_XYZ"),
        "test assumes this env var is not set");
    load(EnvUnsetFallsThroughToMatchIfMissingClass.class);
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @WeaveClass(target = "com.example.EnvPresentMismatchTarget")
  @EnableIf(env = "PATH", havingValue = "__weavergirl_never_matches__")
  public static class EnvPresentMismatchClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void checkEnableIfCondition_envPresent_comparesAgainstHavingValue() {
    // PATH is present in essentially every environment, so envValue != null and the method
    // returns havingValue.equals(envValue) -- exercising the "env value present" branch and its
    // return statement (line 1388), even though the outcome here is a deliberate mismatch.
    assertNotNull(System.getenv("PATH"), "test assumes PATH is set in the environment");
    load(EnvPresentMismatchClass.class);
    assertTrue(registry.getAllDefinitions().isEmpty());
  }

  // ==================== Item 4: resolvePointcutKey (named pointcuts + typed overloads) ====================

  @WeaveClass(target = "com.example.NamedPointcutTarget")
  public static class NamedPointcutReferenceClass {
    @com.github.cc11001100.weavergirl.annotation.Pointcut("execution(* com.example.Foo.bar(..))")
    void myPointcut() {}

    @Before("myPointcut()")
    public void beforeViaNamedReference(MethodInvocation inv) {}
  }

  @Test
  void resolvePointcutKey_namedPointcutReference_resolvesToUnderlyingExpression() {
    load(NamedPointcutReferenceClass.class);
    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    // "myPointcut()" must be replaced by the resolved expression's toString(), not left as-is.
    assertFalse(defs.get(0).getName().contains("myPointcut"));
    assertTrue(defs.get(0).getName().contains("execution("));
  }

  @WeaveClass(target = "com.example.SignatureLookingLikeRefTarget")
  public static class SignatureNotMisparsedAsPointcutRefClass {
    // "save(int)" has non-empty content inside the parens ("int"), so it must NOT be treated as
    // a named-pointcut reference (which requires empty/whitespace-only parens).
    @After("save(int)")
    public void afterSave(MethodInvocation inv) {}
  }

  @Test
  void resolvePointcutKey_methodSignatureLookingLikePointcutRef_isNotMisparsed() {
    load(SignatureNotMisparsedAsPointcutRefClass.class);
    InterceptorDefinition def = definitionEndingWith("-save(int)");
    assertEquals(
        MethodMatcher.MatchType.SIGNATURE, def.getPointcut().getMethodMatcher().getMatchType());
    assertEquals("save(int)", def.getPointcut().getMethodMatcher().getPattern());
  }

  @WeaveClass(target = "com.example.UndefinedPointcutRefTarget")
  public static class UndefinedPointcutReferenceClass {
    // No @Pointcut named "undefinedPointcut" exists on this class, so the lookup misses and the
    // raw key is returned unchanged (namedPointcuts.get(name) == null fallthrough).
    @Before("undefinedPointcut()")
    public void beforeUndefined(MethodInvocation inv) {}
  }

  @Test
  void resolvePointcutKey_namedPointcutReferenceNotFound_returnsRawKeyUnchanged() {
    load(UndefinedPointcutReferenceClass.class);
    assertDoesNotThrow(() -> definitionEndingWith("-undefinedPointcut()"));
  }

  @WeaveClass(target = "com.example.TypedOverloadsTarget")
  public static class TypedResolvePointcutKeyOverloadsClass {
    @Before(value = "beforeSig", parameterTypes = {"int", "java.lang.String"})
    public void beforeMethod(MethodInvocation inv) {}

    @After(value = "afterSig", parameterTypes = {"int"})
    public void afterMethod(MethodInvocation inv) {}

    @AfterReturning(value = "afterReturningSig", parameterTypes = {"int"})
    public void afterReturningMethod(MethodInvocation inv) {}

    @AfterThrowing(value = "afterThrowingSig", parameterTypes = {"int"})
    public void afterThrowingMethod(MethodInvocation inv) {}

    @OnException(value = "onExceptionSig", parameterTypes = {"int"})
    public void onExceptionMethod(MethodInvocation inv) {}

    @Around(value = "aroundSig", parameterTypes = {"int"})
    public void aroundMethod(MethodInvocation inv) {}
  }

  @Test
  void resolvePointcutKey_typedOverloads_appendParameterTypesWhenNonEmpty() {
    load(TypedResolvePointcutKeyOverloadsClass.class);

    assertSignatureDefinition("-beforeSig(int,java.lang.String)", "beforeSig(int,java.lang.String)");
    assertSignatureDefinition("-afterSig(int)", "afterSig(int)");
    assertSignatureDefinition("-afterReturningSig(int)", "afterReturningSig(int)");
    assertSignatureDefinition("-afterThrowingSig(int)", "afterThrowingSig(int)");
    assertSignatureDefinition("-onExceptionSig(int)", "onExceptionSig(int)");
    assertSignatureDefinition("-aroundSig(int)", "aroundSig(int)");
  }

  private void assertSignatureDefinition(String nameSuffix, String expectedPattern) {
    InterceptorDefinition def = definitionEndingWith(nameSuffix);
    assertEquals(
        MethodMatcher.MatchType.SIGNATURE, def.getPointcut().getMethodMatcher().getMatchType());
    assertEquals(expectedPattern, def.getPointcut().getMethodMatcher().getPattern());
  }

  // ==================== Item 5: buildClassMatcherFromAnnotation's 5 branches ====================

  @WeaveClass(targetAnnotation = "com.example.SomeAnnotation")
  public static class ByTargetAnnotationClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void buildClassMatcherFromAnnotation_targetAnnotation_producesAnnotationMatcher() {
    load(ByTargetAnnotationClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
    assertEquals(ClassMatcher.MatchType.ANNOTATION, classMatcher.getMatchType());
    assertEquals("com.example.SomeAnnotation", classMatcher.getPattern());
  }

  @WeaveClass(targetSuperClass = "com.example.BaseService")
  public static class ByTargetSuperClassClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void buildClassMatcherFromAnnotation_targetSuperClass_producesSuperClassMatcher() {
    load(ByTargetSuperClassClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
    assertEquals(ClassMatcher.MatchType.SUPER_CLASS, classMatcher.getMatchType());
    assertEquals("com.example.BaseService", classMatcher.getPattern());
  }

  @WeaveClass(targetInterface = "com.example.SomeInterface")
  public static class ByTargetInterfaceClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void buildClassMatcherFromAnnotation_targetInterface_producesInterfaceMatcher() {
    load(ByTargetInterfaceClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
    assertEquals(ClassMatcher.MatchType.INTERFACE, classMatcher.getMatchType());
    assertEquals("com.example.SomeInterface", classMatcher.getPattern());
  }

  @WeaveClass(targetPattern = "com\\.example\\..*Service")
  public static class ByTargetPatternClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void buildClassMatcherFromAnnotation_targetPattern_producesNamePatternMatcher() {
    load(ByTargetPatternClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
    assertEquals(ClassMatcher.MatchType.NAME_PATTERN, classMatcher.getMatchType());
    assertEquals("com\\.example\\..*Service", classMatcher.getPattern());
  }

  @WeaveClass(target = "com.example.ExactTarget")
  public static class ByTargetExactNameClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void buildClassMatcherFromAnnotation_target_producesExactNameMatcher() {
    load(ByTargetExactNameClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
    assertEquals(ClassMatcher.MatchType.EXACT_NAME, classMatcher.getMatchType());
    assertEquals("com.example.ExactTarget", classMatcher.getPattern());
  }

  // ==================== Item 6: field get/set reflective-invocation error paths ====================

  @WeaveClass(target = "com.example.FieldErrorTarget")
  public static class FieldErrorClass {
    @OnFieldGet("value")
    public void onGet(MethodInvocation inv) {
      throw new RuntimeException("field-get handler deliberately fails");
    }

    @OnFieldSet("value")
    public void onSet(MethodInvocation inv) {
      throw new RuntimeException("field-set handler deliberately fails");
    }
  }

  @Test
  void createFieldGetInterceptor_swallowsExceptionThrownByAnnotatedMethod() {
    load(FieldErrorClass.class);
    InterceptorDefinition def = definitionEndingWith("-fieldGet-value");
    Interceptor interceptor = def.getInterceptor();
    MethodInvocation inv =
        new MethodInvocation(FieldErrorClass.class, "value", new FieldErrorClass(), new Object[0]);

    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void createFieldSetInterceptor_swallowsExceptionThrownByAnnotatedMethod() {
    load(FieldErrorClass.class);
    InterceptorDefinition def = definitionEndingWith("-fieldSet-value");
    Interceptor interceptor = def.getInterceptor();
    MethodInvocation inv =
        new MethodInvocation(
            FieldErrorClass.class, "value", new FieldErrorClass(), new Object[] {"newValue"});

    assertDoesNotThrow(() -> interceptor.before(inv));
  }

  // ==================== Item 7: ReflectiveCatchInterceptor.onCatch error path ====================

  @WeaveClass(target = "com.example.CatchErrorTarget")
  public static class CatchErrorClass {
    @OnCatch("java.lang.RuntimeException")
    public void onErr(CatchInvocation invocation) {
      throw new IllegalStateException("catch handler deliberately fails");
    }
  }

  @Test
  void reflectiveCatchInterceptor_onCatch_swallowsExceptionThrownByAnnotatedMethod() {
    load(CatchErrorClass.class);
    InterceptorDefinition def = definitionEndingWith("-onCatch-onErr");
    CatchInterceptor interceptor = (CatchInterceptor) def.getInterceptor();
    CatchInvocation invocation =
        new CatchInvocation(CatchErrorClass.class, "process", new RuntimeException("boom"));

    assertDoesNotThrow(() -> interceptor.onCatch(invocation));
  }

  // ==================== Item 8: @WeaveClass(pointcut=...) early-return branch ====================

  @WeaveClass(pointcut = "execution(* com.example..*(..)) && @annotation(com.example.Traced)")
  public static class PointcutExpressionModeClass {
    // If the PointcutExpression-mode early return (line 354) did not fire, this class would fall
    // through to buildClassMatcherFromAnnotation, which would call ClassMatcher.byName("") on the
    // empty `target` attribute and throw IllegalArgumentException -- so a clean single
    // registration here is strong evidence the early return executed.
    @Before("irrelevant")
    public void beforeIrrelevant(MethodInvocation inv) {}
  }

  @Test
  void weaveClassPointcutMode_takesEarlyReturn_andRegistersSingleAnnotationNamedDefinition() {
    assertDoesNotThrow(() -> load(PointcutExpressionModeClass.class));
    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals("annotation-PointcutExpressionModeClass", defs.get(0).getName());
  }

  // ==================== Item 9: recordTiming (@Timed) ====================

  @WeaveClass(target = "com.example.TimedLabelTarget")
  @Timed(log = true, label = "customLabel")
  public static class TimedWithLabelClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void recordTiming_logTrueWithExplicitLabel_setsElapsedAttachmentsWithoutThrowing() {
    load(TimedWithLabelClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    Interceptor interceptor = def.getInterceptor();
    MethodInvocation inv =
        new MethodInvocation(TimedWithLabelClass.class, "save", "target", new Object[0]);

    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.after(inv);
        });
    assertNotNull(inv.getAttachment("timed.elapsedNanos"));
    assertNotNull(inv.getAttachment("timed.elapsedMs"));
  }

  @WeaveClass(target = "com.example.TimedNoLabelTarget")
  @Timed(log = true)
  public static class TimedWithoutLabelClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void recordTiming_logTrueWithEmptyLabel_fallsBackToMethodNameWithoutThrowing() {
    load(TimedWithoutLabelClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    Interceptor interceptor = def.getInterceptor();
    MethodInvocation inv =
        new MethodInvocation(TimedWithoutLabelClass.class, "save", "target", new Object[0]);

    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.after(inv);
        });
    assertNotNull(inv.getAttachment("timed.elapsedNanos"));
    assertNotNull(inv.getAttachment("timed.elapsedMs"));
  }

  @WeaveClass(target = "com.example.TimedNoLogTarget")
  @Timed(log = false)
  public static class TimedWithLogDisabledClass {
    @Before("save")
    public void beforeSave(MethodInvocation inv) {}
  }

  @Test
  void recordTiming_logFalse_stillSetsElapsedAttachmentsButSkipsLogging() {
    load(TimedWithLogDisabledClass.class);
    InterceptorDefinition def = definitionEndingWith("-save");
    Interceptor interceptor = def.getInterceptor();
    MethodInvocation inv =
        new MethodInvocation(TimedWithLogDisabledClass.class, "save", "target", new Object[0]);

    assertDoesNotThrow(
        () -> {
          interceptor.before(inv);
          interceptor.after(inv);
        });
    assertNotNull(inv.getAttachment("timed.elapsedNanos"));
    assertNotNull(inv.getAttachment("timed.elapsedMs"));
  }
}
