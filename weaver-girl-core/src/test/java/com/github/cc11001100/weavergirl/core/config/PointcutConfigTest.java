package com.github.cc11001100.weavergirl.core.config;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PointcutConfigTest {

  private static final String NOOP_INTERCEPTOR =
      "com.github.cc11001100.weavergirl.core.config.PointcutConfigTest$NoopInterceptor";

  public static class NoopInterceptor implements Interceptor {}

  private InterceptorRegistry registry;
  private YamlConfigLoader loader;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    loader = new YamlConfigLoader();
  }

  @Test
  void loadYaml_withClassAnnotation_shouldCreateAnnotationMatcher() {
    String yaml =
        "interceptors:\n"
            + "  - classAnnotation: \"com.example.Trace\"\n"
            + "    method: \"process\"\n"
            + "    around: \""
            + NOOP_INTERCEPTOR
            + "\"\n";

    loader.loadFromString(yaml, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());

    Pointcut pointcut = defs.get(0).getPointcut();
    ClassMatcher classMatcher = pointcut.getClassMatcher();
    assertEquals(ClassMatcher.MatchType.ANNOTATION, classMatcher.getMatchType());
    assertEquals("com.example.Trace", classMatcher.getPattern());
  }

  @Test
  void loadYaml_withSuperClass_shouldCreateSuperClassMatcher() {
    String yaml =
        "interceptors:\n"
            + "  - superClass: \"com.example.BaseService\"\n"
            + "    method: \"doWork\"\n"
            + "    around: \""
            + NOOP_INTERCEPTOR
            + "\"\n";

    loader.loadFromString(yaml, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());

    Pointcut pointcut = defs.get(0).getPointcut();
    ClassMatcher classMatcher = pointcut.getClassMatcher();
    assertEquals(ClassMatcher.MatchType.SUPER_CLASS, classMatcher.getMatchType());
    assertEquals("com.example.BaseService", classMatcher.getPattern());
  }

  @Test
  void loadYaml_withPointcutExpression_shouldParseExpression() {
    String yaml =
        "interceptors:\n"
            + "  - pointcut: \"execution(* com.example.Service.process(..))\"\n"
            + "    around: \""
            + NOOP_INTERCEPTOR
            + "\"\n";

    loader.loadFromString(yaml, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());

    InterceptorDefinition def = defs.get(0);
    assertTrue(
        def.getName().startsWith("yaml-pointcut-"),
        "Definition name should start with 'yaml-pointcut-' but was: " + def.getName());

    Pointcut pointcut = def.getPointcut();
    assertNotNull(pointcut);
    assertNotNull(pointcut.getClassMatcher());
    assertNotNull(pointcut.getMethodMatcher());
  }

  @Test
  void loadYaml_withMethodAnnotation_shouldCreateAnnotationMethodMatcher() {
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.Service\"\n"
            + "    methodAnnotation: \"com.example.Traced\"\n"
            + "    around: \""
            + NOOP_INTERCEPTOR
            + "\"\n";

    loader.loadFromString(yaml, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());

    Pointcut pointcut = defs.get(0).getPointcut();
    MethodMatcher methodMatcher = pointcut.getMethodMatcher();
    assertEquals(MethodMatcher.MatchType.ANNOTATION, methodMatcher.getMatchType());
    assertEquals("com.example.Traced", methodMatcher.getPattern());
  }

  @Test
  void loadYaml_backwardCompatible_oldFormatStillWorks() {
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.UserService\"\n"
            + "    method: \"createUser\"\n"
            + "    around: \""
            + NOOP_INTERCEPTOR
            + "\"\n";

    loader.loadFromString(yaml, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());

    InterceptorDefinition def = defs.get(0);
    assertTrue(
        def.getName().startsWith("yaml-com.example.UserService-"),
        "Definition name should start with 'yaml-com.example.UserService-' but was: "
            + def.getName());

    Pointcut pointcut = def.getPointcut();
    ClassMatcher classMatcher = pointcut.getClassMatcher();
    assertEquals(ClassMatcher.MatchType.EXACT_NAME, classMatcher.getMatchType());
    assertEquals("com.example.UserService", classMatcher.getPattern());

    MethodMatcher methodMatcher = pointcut.getMethodMatcher();
    assertEquals(MethodMatcher.MatchType.EXACT_NAME, methodMatcher.getMatchType());
    assertEquals("createUser", methodMatcher.getPattern());
  }
}
