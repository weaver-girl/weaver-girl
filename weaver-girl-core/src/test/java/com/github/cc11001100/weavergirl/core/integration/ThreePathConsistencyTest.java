package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutParser;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.config.YamlConfigLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integration test verifying that all three interceptor registration paths (Java API fluent
 * builder, Java API PointcutExpression, and YAML config) produce equivalent Pointcut structures for
 * the same logical matching.
 *
 * @since 1.1.0
 */
class ThreePathConsistencyTest {

  private static final String NOOP_CLASS =
      "com.github.cc11001100.weavergirl.core.integration.ThreePathConsistencyTest$Noop";

  /** No-op interceptor used by YAML paths. */
  public static class Noop implements Interceptor {}

  // -----------------------------------------------------------------------
  // Test 1: exact class + exact method
  // -----------------------------------------------------------------------

  @Test
  void exactClassAndMethod_allPathsProduceSamePointcut() {
    // --- Path 1: Fluent builder ---
    WeaverGirl wg1 = WeaverGirl.create();
    wg1.intercept("com.example.Service").method("process").before(inv -> {}).install();
    Pointcut p1 = lastDefinition(wg1.getRegistry()).getPointcut();

    // --- Path 2: PointcutExpression ---
    WeaverGirl wg2 = WeaverGirl.create();
    wg2.interceptExpression("execution(* com.example.Service.process(..))")
        .before(inv -> {})
        .install();
    Pointcut p2 = lastDefinition(wg2.getRegistry()).getPointcut();

    // --- Path 3: YAML with className + method ---
    DefaultInterceptorRegistry reg3 = new DefaultInterceptorRegistry();
    YamlConfigLoader loader = new YamlConfigLoader();
    String yaml3 =
        "interceptors:\n"
            + "  - className: com.example.Service\n"
            + "    method: process\n"
            + "    around: "
            + NOOP_CLASS
            + "\n";
    loader.loadFromString(yaml3, reg3);
    Pointcut p3 = lastDefinition(reg3).getPointcut();

    // --- Path 4: YAML with pointcut expression ---
    DefaultInterceptorRegistry reg4 = new DefaultInterceptorRegistry();
    String yaml4 =
        "interceptors:\n"
            + "  - pointcut: execution(* com.example.Service.process(..))\n"
            + "    around: "
            + NOOP_CLASS
            + "\n";
    loader.loadFromString(yaml4, reg4);
    Pointcut p4 = lastDefinition(reg4).getPointcut();

    // --- Verify ALL four produce EXACT_NAME class + EXACT_NAME method ---
    for (int i = 0; i < 4; i++) {
      Pointcut p = new Pointcut[] {p1, p2, p3, p4}[i];
      assertEquals(
          ClassMatcher.MatchType.EXACT_NAME,
          p.getClassMatcher().getMatchType(),
          "Path " + (i + 1) + " class matcher type");
      assertEquals(
          "com.example.Service",
          p.getClassMatcher().getPattern(),
          "Path " + (i + 1) + " class pattern");
      assertEquals(
          MethodMatcher.MatchType.EXACT_NAME,
          p.getMethodMatcher().getMatchType(),
          "Path " + (i + 1) + " method matcher type");
      assertEquals(
          "process", p.getMethodMatcher().getPattern(), "Path " + (i + 1) + " method pattern");
    }
  }

  // -----------------------------------------------------------------------
  // Test 2: method-level annotation matching
  // -----------------------------------------------------------------------

  @Test
  void annotationMatching_allPathsProduceSamePointcut() {
    // --- Path 1: Direct InterceptorDefinition with method annotation matcher ---
    DefaultInterceptorRegistry reg1 = new DefaultInterceptorRegistry();
    Pointcut pointcut1 =
        new Pointcut(
            ClassMatcher.byName("java.lang.Object"),
            MethodMatcher.byAnnotation("com.example.Traced"));
    reg1.register(new InterceptorDefinition("anno-path1", pointcut1, new Noop()));
    Pointcut p1 = lastDefinition(reg1).getPointcut();

    // --- Path 2: PointcutExpression @annotation ---
    WeaverGirl wg2 = WeaverGirl.create();
    wg2.interceptExpression("@annotation(com.example.Traced)").before(inv -> {}).install();
    Pointcut p2 = lastDefinition(wg2.getRegistry()).getPointcut();

    // --- Path 3: YAML with className + methodAnnotation ---
    DefaultInterceptorRegistry reg3 = new DefaultInterceptorRegistry();
    YamlConfigLoader loader = new YamlConfigLoader();
    String yaml3 =
        "interceptors:\n"
            + "  - className: java.lang.Object\n"
            + "    methodAnnotation: com.example.Traced\n"
            + "    around: "
            + NOOP_CLASS
            + "\n";
    loader.loadFromString(yaml3, reg3);
    Pointcut p3 = lastDefinition(reg3).getPointcut();

    // --- Path 4: YAML with pointcut: @annotation(...) ---
    DefaultInterceptorRegistry reg4 = new DefaultInterceptorRegistry();
    String yaml4 =
        "interceptors:\n"
            + "  - pointcut: \"@annotation(com.example.Traced)\"\n"
            + "    around: "
            + NOOP_CLASS
            + "\n";
    loader.loadFromString(yaml4, reg4);
    Pointcut p4 = lastDefinition(reg4).getPointcut();

    // --- Verify ALL four produce ANNOTATION method matcher ---
    for (int i = 0; i < 4; i++) {
      Pointcut p = new Pointcut[] {p1, p2, p3, p4}[i];
      assertEquals(
          MethodMatcher.MatchType.ANNOTATION,
          p.getMethodMatcher().getMatchType(),
          "Path " + (i + 1) + " method matcher type");
      assertEquals(
          "com.example.Traced",
          p.getMethodMatcher().getPattern(),
          "Path " + (i + 1) + " annotation pattern");
    }
  }

  // -----------------------------------------------------------------------
  // Test 3: class-level annotation matching
  // -----------------------------------------------------------------------

  @Test
  void classAnnotation_allPathsProduceSamePointcut() {
    // --- Path 1: Direct InterceptorDefinition with class annotation matcher ---
    DefaultInterceptorRegistry reg1 = new DefaultInterceptorRegistry();
    Pointcut pointcut1 =
        new Pointcut(ClassMatcher.byAnnotation("com.example.Monitored"), MethodMatcher.any());
    reg1.register(new InterceptorDefinition("classAnno-path1", pointcut1, new Noop()));
    Pointcut p1 = lastDefinition(reg1).getPointcut();

    // --- Path 2: PointcutParser.parse("@within(...)").toPointcut() ---
    PointcutExpression expr = PointcutParser.getInstance().parse("@within(com.example.Monitored)");
    Pointcut p2 = expr.toPointcut();

    // --- Path 3: YAML with classAnnotation ---
    DefaultInterceptorRegistry reg3 = new DefaultInterceptorRegistry();
    YamlConfigLoader loader = new YamlConfigLoader();
    String yaml3 =
        "interceptors:\n"
            + "  - classAnnotation: com.example.Monitored\n"
            + "    around: "
            + NOOP_CLASS
            + "\n";
    loader.loadFromString(yaml3, reg3);
    Pointcut p3 = lastDefinition(reg3).getPointcut();

    // --- Path 4: YAML with pointcut: @within(...) ---
    DefaultInterceptorRegistry reg4 = new DefaultInterceptorRegistry();
    String yaml4 =
        "interceptors:\n"
            + "  - pointcut: \"@within(com.example.Monitored)\"\n"
            + "    around: "
            + NOOP_CLASS
            + "\n";
    loader.loadFromString(yaml4, reg4);
    Pointcut p4 = lastDefinition(reg4).getPointcut();

    // --- Verify ALL four produce ANNOTATION class matcher ---
    for (int i = 0; i < 4; i++) {
      Pointcut p = new Pointcut[] {p1, p2, p3, p4}[i];
      assertEquals(
          ClassMatcher.MatchType.ANNOTATION,
          p.getClassMatcher().getMatchType(),
          "Path " + (i + 1) + " class matcher type");
      assertEquals(
          "com.example.Monitored",
          p.getClassMatcher().getPattern(),
          "Path " + (i + 1) + " class annotation pattern");
    }
  }

  // -----------------------------------------------------------------------
  // Helper
  // -----------------------------------------------------------------------

  /** Returns the last registered definition from the given registry. */
  private static InterceptorDefinition lastDefinition(InterceptorRegistry registry) {
    List<InterceptorDefinition> all = registry.getAllDefinitions();
    assertFalse(all.isEmpty(), "Registry should contain at least one definition");
    return all.get(all.size() - 1);
  }
}
