package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
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

  @Test
  void parsesAtTargetExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression = parser.parse("@target(com.example.Traced)");
    assertEquals(PointcutExpression.Type.AT_TARGET, expression.getType());
    assertEquals("com.example.Traced", expression.getTargetAnnotationClassName());
    assertEquals("@target(com.example.Traced)", expression.toString());
    assertTrue(expression.toPointcut().getClassMatcher().matches("com.example.Traced"));
    assertEquals(MethodMatcher.any(), expression.toPointcut().getMethodMatcher());
  }

  @Test
  void parsesAtArgsExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression =
        parser.parse("@args(com.example.Secure, 0, 2)");
    assertEquals(PointcutExpression.Type.AT_ARGS, expression.getType());
    assertEquals("com.example.Secure", expression.getArgumentAnnotationClassName());
    assertArrayEquals(new int[]{0, 2}, expression.getArgumentIndexes());
    assertEquals("@args(com.example.Secure, [0, 2])", expression.toString());
    assertTrue(expression.toPointcut().getClassMatcher().matches("any.class"));
    MethodMatcher methodMatcher = expression.toPointcut().getMethodMatcher();
    assertEquals(MethodMatcher.MatchType.ARGS, methodMatcher.getMatchType());
  }

  @Test
  void parsesAtArgsWithSingleIndex() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression =
        parser.parse("@args(com.example.Secure, 0)");
    assertEquals(PointcutExpression.Type.AT_ARGS, expression.getType());
    assertArrayEquals(new int[]{0}, expression.getArgumentIndexes());
  }

  @Test
  void compositeExpressionsWithAtArgsAndAtTarget() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression =
        parser.parse("@target(com.example.Traced) && @args(com.example.Secure, 0)");
    assertEquals(PointcutExpression.Type.AND, expression.getType());
    assertEquals(PointcutExpression.Type.AT_TARGET, expression.getLeft().getType());
    assertEquals(PointcutExpression.Type.AT_ARGS, expression.getRight().getType());
    Pointcut pointcut = expression.toPointcut();
    assertTrue(pointcut.getClassMatcher().matches("com.example.Traced"));
    assertEquals(MethodMatcher.MatchType.ARGS, pointcut.getMethodMatcher().getMatchType());
  }

  @Test
  void rejectsMalformedAtArgsExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    assertThrows(IllegalArgumentException.class, () -> parser.parse("@args(com.example.Secure)"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("@args(com.example.Secure,)"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("@args(com.example.Secure, x)"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("@args(com.example.Secure, -1)"));
  }

  @Test
  void rejectsMalformedAtTargetExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    assertThrows(IllegalArgumentException.class, () -> parser.parse("@target()"));
  }

  @Test
  void parsesCallExpressionWithReturnType() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression = parser.parse("call(* com.example.Service.process(..))");
    assertEquals(PointcutExpression.Type.CALL, expression.getType());
    assertEquals("*", expression.getCallReturnType());
    assertEquals("com.example.Service", expression.getCallClassPattern());
    assertEquals("process", expression.getCallMethodPattern());
    assertEquals("..", expression.getCallParamPattern());
  }

  @Test
  void parsesCallExpressionWithoutReturnType() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression = parser.parse("call(com.example.Service.process(..))");
    assertEquals(PointcutExpression.Type.CALL, expression.getType());
    assertNull(expression.getCallReturnType());
    assertEquals("com.example.Service", expression.getCallClassPattern());
    assertEquals("process", expression.getCallMethodPattern());
  }

  @Test
  void rejectsMalformedCallExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    assertThrows(IllegalArgumentException.class, () -> parser.parse("call(bad)"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("call(NoDotHere(..))"));
  }

  @Test
  void parsesHandlerExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression = parser.parse("handler(java.io.IOException)");
    assertEquals(PointcutExpression.Type.HANDLER, expression.getType());
    assertEquals("java.io.IOException", expression.getHandlerExceptionType());
  }

  @Test
  void parsesCflowExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression =
        parser.parse("cflow(execution(* com.example.Caller.call(..)))");
    assertEquals(PointcutExpression.Type.CFLOW, expression.getType());
    PointcutExpression inner = expression.getCflowExpression();
    assertEquals(PointcutExpression.Type.EXECUTION, inner.getType());
    assertEquals("com.example.Caller", inner.getClassPattern());
    assertEquals("call", inner.getMethodPattern());

    // cflow() is a runtime condition — statically it always matches.
    assertTrue(expression.toPointcut().matches("anything.At.All", "anyMethod"));
  }

  @Test
  void parsesIfExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression = parser.parse("if(args.length > 0)");
    assertEquals(PointcutExpression.Type.IF, expression.getType());
    assertEquals("args.length > 0", expression.getIfCondition());
    assertTrue(expression.toPointcut().matches("anything.At.All", "anyMethod"));
  }

  @Test
  void parsesIfWithEmptyCondition() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression = parser.parse("if()");
    assertEquals(PointcutExpression.Type.IF, expression.getType());
    assertEquals("", expression.getIfCondition());
    // An empty if() condition defaults to true so it doesn't silently disable advice.
    assertTrue(expression.evaluateRuntimeCondition("c", "m", null, null, null));
  }

  @Test
  void parsesCflowAndIfCombinedWithLogicalOperators() {
    PointcutParser parser = PointcutParser.getInstance();

    PointcutExpression andExpr =
        parser.parse("cflow(execution(* com.example.A.b(..))) && @annotation(com.example.Trace)");
    assertEquals(PointcutExpression.Type.AND, andExpr.getType());
    assertEquals(PointcutExpression.Type.CFLOW, andExpr.getLeft().getType());
    assertEquals(PointcutExpression.Type.AT_ANNOTATION, andExpr.getRight().getType());

    PointcutExpression orExpr = parser.parse("if(args.length > 1) || within(com.example..)");
    assertEquals(PointcutExpression.Type.OR, orExpr.getType());
    assertEquals(PointcutExpression.Type.IF, orExpr.getLeft().getType());
    assertEquals(PointcutExpression.Type.WITHIN, orExpr.getRight().getType());
  }

  @Test
  void parsesNestedCflowWithCompositeInnerExpression() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression =
        parser.parse("cflow(execution(* com.example.A.b(..)) && @annotation(com.example.Trace))");
    assertEquals(PointcutExpression.Type.CFLOW, expression.getType());
    PointcutExpression inner = expression.getCflowExpression();
    assertEquals(PointcutExpression.Type.AND, inner.getType());
    assertEquals(PointcutExpression.Type.EXECUTION, inner.getLeft().getType());
    assertEquals(PointcutExpression.Type.AT_ANNOTATION, inner.getRight().getType());
  }

  @Test
  void rejectsMalformedCflowAndIfExpressions() {
    PointcutParser parser = PointcutParser.getInstance();
    assertThrows(IllegalArgumentException.class, () -> parser.parse("cflow(unbalanced"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("cflow(unknownAtom(x))"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("if("));
  }

  @Test
  void roundTripCflowEvaluatesAgainstLiveCallStack() {
    PointcutParser parser = PointcutParser.getInstance();
    PointcutExpression expression =
        parser.parse("cflow(execution(* com.example.Caller.call(..)))");

    // Outside any call stack, the cflow condition is not satisfied.
    assertFalse(
        expression.evaluateRuntimeCondition("com.example.Target", "target", null, null, null));

    PointcutExpression.enterCflow("com.example.Caller", "call");
    PointcutExpression.enterCflow("com.example.Target", "target");
    try {
      assertTrue(
          expression.evaluateRuntimeCondition("com.example.Target", "target", null, null, null));
    } finally {
      PointcutExpression.exitCflow("com.example.Target", "target");
      PointcutExpression.exitCflow("com.example.Caller", "call");
    }

    // Fully unwound — the condition is no longer satisfied.
    assertFalse(
        expression.evaluateRuntimeCondition("com.example.Target", "target", null, null, null));
  }
}
