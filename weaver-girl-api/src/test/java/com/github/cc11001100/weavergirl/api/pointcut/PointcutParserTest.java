package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import org.junit.jupiter.api.Test;

class PointcutParserTest {

  @Test
  void parsesAllAtomicFormsAndExecutionPatterns() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression execution = parser.parse("execution(* com.example..Service.process(..))");
    assertEquals(PointcutExpression.Type.EXECUTION, execution.getType());
    assertEquals("*", execution.getReturnType());
    assertEquals("com.example..Service", execution.getClassPattern());
    assertEquals("process", execution.getMethodPattern());
    assertEquals("..", execution.getParamPattern());
    assertTrue(execution.toPointcut().matches("com.example.foo.Service", "process"));
    assertFalse(execution.toPointcut().matches("com.example.foo.Other", "process"));

    assertEquals(
        PointcutExpression.Type.AT_ANNOTATION,
        parser.parse("@annotation(com.example.Trace)").getType());
    assertEquals(
        "com.example.Trace",
        parser.parse("@annotation(com.example.Trace)").getAnnotationClassName());
    assertEquals(PointcutExpression.Type.AT_WITHIN, parser.parse("@within(A)").getType());
    assertEquals(PointcutExpression.Type.WITHIN, parser.parse("within(com.example..)").getType());
    assertEquals(PointcutExpression.Type.SUBCLASS_OF, parser.parse("subclassOf(A)").getType());
    assertEquals(PointcutExpression.Type.IMPLEMENTING, parser.parse("implementing(A)").getType());
  }

  @Test
  void parsesOperatorsWithPrecedenceAndParentheses() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression =
        parser.parse("!(within(com.example..) || @annotation(Trace)) && within(org.example..)");
    assertEquals(PointcutExpression.Type.AND, expression.getType());
    assertEquals(PointcutExpression.Type.NOT, expression.getLeft().getType());
    assertEquals(PointcutExpression.Type.OR, expression.getLeft().getOperand().getType());
    assertEquals(
        "(!((within(com.example..) || @annotation(Trace))) && within(org.example..))",
        expression.toString());
    assertFalse(expression.toPointcut().matches("org.example.Service", "run"));
    assertFalse(expression.toPointcut().matches("com.example.Service", "run"));

    Pointcut scoped =
        parser
            .parse("execution(* *.run(..)) && @annotation(Trace)")
            .toPointcutWithClassScope(ClassMatcher.byName("Example"));
    assertTrue(scoped.getClassMatcher().matches("Example"));
    assertTrue(scoped.getMethodMatcher().matches("run"));
  }

  @Test
  void rejectsMalformedExpressions() {
    PointcutParser parser = PointcutParser.getInstance();
    assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("  "));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("!"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("execution(bad)"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("execution(* Service.run)"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("unknown(A)"));
  }

  @Test
  void factoryGettersAndRegexConversionAreStable() {
    PointcutExpression[] expressions = {
      PointcutExpression.execution("void", "a.*", "run*", "String"),
      PointcutExpression.atAnnotation("A"),
      PointcutExpression.atWithin("B"),
      PointcutExpression.within("a.."),
      PointcutExpression.subclassOf("C"),
      PointcutExpression.implementing("D")
    };
    assertEquals("execution(void a.*.run*(String))", expressions[0].toString());
    assertEquals("A", expressions[1].getAnnotationClassName());
    assertEquals("B", expressions[2].getAnnotationClassName());
    assertEquals("a..", expressions[3].getPackageName());
    assertEquals("C", expressions[4].getClassName());
    assertEquals("D", expressions[5].getInterfaceName());
    assertEquals(".*", PointcutExpression.convertToRegex(""));
    assertEquals("a.b[^.]*", PointcutExpression.convertToRegex("a.b*"));
    assertEquals("a.*", PointcutExpression.convertToRegex("a.."));
  }
}
