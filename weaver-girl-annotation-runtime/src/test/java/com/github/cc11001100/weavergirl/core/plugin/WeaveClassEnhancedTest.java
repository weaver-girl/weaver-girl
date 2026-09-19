package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WeaveClassEnhancedTest {

  // --- Test interceptor classes using new @WeaveClass attributes ---

  @WeaveClass(targetAnnotation = "com.example.Monitored")
  public static class AnnotationMatchInterceptor {
    @Before("process")
    public void beforeProcess(MethodInvocation inv) {}
  }

  @WeaveClass(targetSuperClass = "com.example.BaseService")
  public static class SuperClassMatchInterceptor {
    @After("execute")
    public void afterExecute(MethodInvocation inv) {}
  }

  @WeaveClass(targetInterface = "java.io.Serializable")
  public static class InterfaceMatchInterceptor {
    @Before("toString")
    public void beforeToString(MethodInvocation inv) {}
  }

  @WeaveClass(pointcut = "execution(* com.example..*(..)) && @annotation(com.example.Traced)")
  public static class PointcutExpressionInterceptor {
    @Before(".*")
    public void beforeAny(MethodInvocation inv) {}
  }

  @WeaveClass(target = "com.example.UserService")
  public static class LegacyExactMatchInterceptor {
    @Before("create")
    public void beforeCreate(MethodInvocation inv) {}
  }

  @WeaveClass(targetPattern = "com\\.example\\..*Service")
  public static class PatternMatchInterceptor {
    @Before("process")
    public void beforeProcess(MethodInvocation inv) {}
  }

  @Test
  void weaveClass_targetAnnotation_createsAnnotationClassMatcher() {
    InterceptorRegistry registry = new TestInterceptorRegistry();
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(AnnotationMatchInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals(
        ClassMatcher.MatchType.ANNOTATION,
        defs.get(0).getPointcut().getClassMatcher().getMatchType());
    assertEquals("com.example.Monitored", defs.get(0).getPointcut().getClassMatcher().getPattern());
  }

  @Test
  void weaveClass_targetSuperClass_createsSuperClassMatcher() {
    InterceptorRegistry registry = new TestInterceptorRegistry();
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(SuperClassMatchInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals(
        ClassMatcher.MatchType.SUPER_CLASS,
        defs.get(0).getPointcut().getClassMatcher().getMatchType());
    assertEquals(
        "com.example.BaseService", defs.get(0).getPointcut().getClassMatcher().getPattern());
  }

  @Test
  void weaveClass_targetInterface_createsInterfaceMatcher() {
    InterceptorRegistry registry = new TestInterceptorRegistry();
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(InterfaceMatchInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals(
        ClassMatcher.MatchType.INTERFACE,
        defs.get(0).getPointcut().getClassMatcher().getMatchType());
    assertEquals("java.io.Serializable", defs.get(0).getPointcut().getClassMatcher().getPattern());
  }

  @Test
  void weaveClass_pointcut_usesPointcutExpression() {
    InterceptorRegistry registry = new TestInterceptorRegistry();
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(PointcutExpressionInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    // PointcutExpression mode registers a single definition (not per-method)
    assertNotNull(defs.get(0).getPointcut());
    // The expression "execution(* com.example..*(..)) && @annotation(com.example.Traced)"
    // should produce a composite AND pointcut
    assertNotNull(defs.get(0).getPointcut().getClassMatcher());
  }

  @Test
  void weaveClass_legacyTarget_stillWorksBackwardCompatible() {
    InterceptorRegistry registry = new TestInterceptorRegistry();
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(LegacyExactMatchInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals(
        ClassMatcher.MatchType.EXACT_NAME,
        defs.get(0).getPointcut().getClassMatcher().getMatchType());
    assertEquals(
        "com.example.UserService", defs.get(0).getPointcut().getClassMatcher().getPattern());
    assertEquals(
        MethodMatcher.MatchType.EXACT_NAME,
        defs.get(0).getPointcut().getMethodMatcher().getMatchType());
    assertEquals("create", defs.get(0).getPointcut().getMethodMatcher().getPattern());
  }

  @Test
  void weaveClass_targetPattern_createsPatternMatcher() {
    InterceptorRegistry registry = new TestInterceptorRegistry();
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(PatternMatchInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals(
        ClassMatcher.MatchType.NAME_PATTERN,
        defs.get(0).getPointcut().getClassMatcher().getMatchType());
  }
}
