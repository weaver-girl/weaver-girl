package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.Test;

class PointcutExpressionTest {

  @Test
  void factoriesExposeAllExpressionFields() {
    PointcutExpression execution = PointcutExpression.execution("void", "pkg..Service", "do*", "..");
    assertEquals(PointcutExpression.Type.EXECUTION, execution.getType());
    assertEquals("void", execution.getReturnType());
    assertEquals("pkg..Service", execution.getClassPattern());
    assertEquals("do*", execution.getMethodPattern());
    assertEquals("..", execution.getParamPattern());
    assertTrue(execution.toString().startsWith("execution(void"));

    PointcutExpression annotation = PointcutExpression.atAnnotation("pkg.Marked");
    PointcutExpression withinAnnotation = PointcutExpression.atWithin("pkg.TypeMarker");
    PointcutExpression within = PointcutExpression.within("pkg..service");
    PointcutExpression subclass = PointcutExpression.subclassOf("pkg.Base");
    PointcutExpression implementing = PointcutExpression.implementing("pkg.Contract");
    assertEquals("pkg.Marked", annotation.getAnnotationClassName());
    assertEquals("pkg.TypeMarker", withinAnnotation.getAnnotationClassName());
    assertEquals("pkg..service", within.getPackageName());
    assertEquals("pkg.Base", subclass.getClassName());
    assertEquals("pkg.Contract", implementing.getInterfaceName());
    assertTrue(annotation.toString().contains("@annotation"));
    assertTrue(withinAnnotation.toString().contains("@within"));
    assertTrue(within.toString().contains("within"));
    assertTrue(subclass.toString().contains("subclassOf"));
    assertTrue(implementing.toString().contains("implementing"));
  }

  @Test
  void conversionBuildsExpectedMatcherTypes() {
    PointcutExpression exact = PointcutExpression.execution("*", "pkg.Service", "run", "");
    assertEquals(ClassMatcher.MatchType.EXACT_NAME, exact.toPointcut().getClassMatcher().getMatchType());
    assertEquals(MethodMatcher.MatchType.EXACT_NAME, exact.toPointcut().getMethodMatcher().getMatchType());

    PointcutExpression wildcard = PointcutExpression.execution("*", "pkg..*", "run*", "..");
    assertEquals(ClassMatcher.MatchType.NAME_PATTERN, wildcard.toPointcut().getClassMatcher().getMatchType());
    assertEquals(MethodMatcher.MatchType.NAME_PATTERN, wildcard.toPointcut().getMethodMatcher().getMatchType());
    assertEquals(MethodMatcher.MatchType.ANY, PointcutExpression.execution("*", "", "", "").toPointcut().getMethodMatcher().getMatchType());
    assertEquals(MethodMatcher.MatchType.ANNOTATION, PointcutExpression.atAnnotation("pkg.Marked").toPointcut().getMethodMatcher().getMatchType());
    assertEquals(ClassMatcher.MatchType.ANNOTATION, PointcutExpression.atWithin("pkg.Marked").toPointcut().getClassMatcher().getMatchType());
    assertEquals(ClassMatcher.MatchType.SUPER_CLASS, PointcutExpression.subclassOf("pkg.Base").toPointcut().getClassMatcher().getMatchType());
    assertEquals(ClassMatcher.MatchType.INTERFACE, PointcutExpression.implementing("pkg.Contract").toPointcut().getClassMatcher().getMatchType());
  }

  @Test
  void compositesRetainOperandsAndSupportScopedAnnotation() {
    PointcutExpression base = PointcutExpression.within("pkg.*");
    PointcutExpression annotation = PointcutExpression.atAnnotation("pkg.Marked");
    PointcutExpression and = PointcutExpression.and(base, annotation);
    PointcutExpression or = PointcutExpression.or(base, annotation);
    PointcutExpression not = PointcutExpression.not(base);
    assertEquals(base, and.getLeft());
    assertEquals(annotation, and.getRight());
    assertEquals(base, not.getOperand());
    assertTrue(and.toString().contains("&&"));
    assertTrue(or.toString().contains("||"));
    assertTrue(not.toString().startsWith("!("));
    assertEquals(ClassMatcher.MatchType.EXACT_NAME, annotation.toPointcutWithClassScope(ClassMatcher.byName("pkg.Service")).getClassMatcher().getMatchType());
    assertEquals(PointcutExpression.Type.AND, and.getType());
    assertEquals(PointcutExpression.Type.OR, or.getType());
    assertEquals(PointcutExpression.Type.NOT, not.getType());
  }

  @Test
  void wildcardConversionEscapesRegexAndHandlesEmpty() {
    assertEquals(".*", PointcutExpression.convertToRegex(null));
    assertEquals(".*", PointcutExpression.convertToRegex(""));
    String regex = PointcutExpression.convertToRegex("a+b?.[x]|*");
    assertTrue(regex.contains("\\+"));
    assertTrue(regex.contains("\\?"));
    assertTrue(regex.contains("\\["));
    assertTrue(regex.contains("[^.]*"));
  }
}
