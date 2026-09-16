package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PointcutExpressionTest {

  @AfterEach
  void drainCflowStack() {
    // The cflow ThreadLocal persists across tests on the same thread; drain it so
    // one test's pushed frames never leak into the next test's assertions.
    while (PointcutExpression.CflowContext.current() != null) {
      PointcutExpression.exitCflow("drain", "drain");
    }
  }

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

  @Test
  void callAndHandlerFactoriesExposeFields() {
    PointcutExpression call = PointcutExpression.call("*", "pkg.Service", "run*", "..");
    assertEquals(PointcutExpression.Type.CALL, call.getType());
    assertEquals("*", call.getCallReturnType());
    assertEquals("pkg.Service", call.getCallClassPattern());
    assertEquals("run*", call.getCallMethodPattern());
    assertEquals("..", call.getCallParamPattern());
    assertEquals("call(* pkg.Service.run*(..))", call.toString());
    assertEquals(
        MethodMatcher.MatchType.NAME_PATTERN, call.toPointcut().getMethodMatcher().getMatchType());

    PointcutExpression callNoReturn = PointcutExpression.call(null, "pkg.Service", "run", null);
    assertEquals("call(pkg.Service.run(..))", callNoReturn.toString());

    PointcutExpression handler = PointcutExpression.handler("java.io.IOException");
    assertEquals(PointcutExpression.Type.HANDLER, handler.getType());
    assertEquals("java.io.IOException", handler.getHandlerExceptionType());
    assertEquals("handler(java.io.IOException)", handler.toString());
    assertEquals(MethodMatcher.MatchType.EXACT_NAME, handler.toPointcut().getMethodMatcher().getMatchType());
  }

  @Test
  void cflowFactoryExposesFieldsAndAlwaysMatchesStatically() {
    PointcutExpression inner = PointcutExpression.execution("*", "pkg.Caller", "call", "..");
    PointcutExpression cflow = PointcutExpression.cflow(inner);

    assertEquals(PointcutExpression.Type.CFLOW, cflow.getType());
    assertEquals(inner, cflow.getCflowExpression());
    assertEquals(inner, cflow.getOperand());

    assertEquals(ClassMatcher.MatchType.ANY, cflow.toPointcut().getClassMatcher().getMatchType());
    assertEquals(MethodMatcher.MatchType.ANY, cflow.toPointcut().getMethodMatcher().getMatchType());
    assertEquals(
        ClassMatcher.MatchType.ANY,
        cflow.toPointcutWithClassScope(ClassMatcher.byName("x")).getClassMatcher().getMatchType());

    // CFLOW has no dedicated toString() branch — falls through to the default representation.
    assertEquals("PointcutExpression{type=CFLOW}", cflow.toString());
  }

  @Test
  void ifConditionFactoryExposesFieldAndAlwaysMatchesStatically() {
    PointcutExpression ifExpr = PointcutExpression.ifCondition("args.length > 0");
    assertEquals(PointcutExpression.Type.IF, ifExpr.getType());
    assertEquals("args.length > 0", ifExpr.getIfCondition());

    assertEquals(ClassMatcher.MatchType.ANY, ifExpr.toPointcut().getClassMatcher().getMatchType());
    assertEquals(MethodMatcher.MatchType.ANY, ifExpr.toPointcut().getMethodMatcher().getMatchType());
    assertEquals(
        MethodMatcher.MatchType.ANY,
        ifExpr.toPointcutWithClassScope(ClassMatcher.byName("x")).getMethodMatcher().getMatchType());

    // IF has no dedicated toString() branch — falls through to the default representation.
    assertEquals("PointcutExpression{type=IF}", ifExpr.toString());
  }

  @Test
  void toPointcutWithClassScope_coversRemainingBranches() {
    ClassMatcher scope = ClassMatcher.byName("pkg.Scoped");
    assertEquals(
        MethodMatcher.MatchType.NAME_PATTERN,
        PointcutExpression.call("*", "pkg.Service", "run*", "..")
            .toPointcutWithClassScope(scope)
            .getMethodMatcher()
            .getMatchType());
    assertEquals(
        MethodMatcher.MatchType.EXACT_NAME,
        PointcutExpression.handler("java.io.IOException")
            .toPointcutWithClassScope(scope)
            .getMethodMatcher()
            .getMatchType());

    PointcutExpression and =
        PointcutExpression.and(
            PointcutExpression.execution("*", "pkg.A", "m", ".."),
            PointcutExpression.execution("*", "pkg.B", "n", ".."));
    PointcutExpression or =
        PointcutExpression.or(
            PointcutExpression.execution("*", "pkg.A", "m", ".."),
            PointcutExpression.execution("*", "pkg.B", "n", ".."));
    PointcutExpression not = PointcutExpression.not(PointcutExpression.execution("*", "pkg.A", "m", ".."));

    assertFalse(and.toPointcutWithClassScope(scope).matches("pkg.A", "m"));
    assertFalse(and.toPointcutWithClassScope(scope).matches("pkg.Other", "x"));
    assertTrue(or.toPointcutWithClassScope(scope).matches("pkg.A", "m"));
    assertFalse(not.toPointcutWithClassScope(scope).matches("pkg.A", "m"));
  }

  @Test
  void evaluateRuntimeCondition_staticMatcherTypesAlwaysTrue() {
    PointcutExpression execution = PointcutExpression.execution("*", "pkg.A", "m", "..");
    assertTrue(execution.evaluateRuntimeCondition("pkg.A", "m", new Object[0], null, null));
    assertTrue(PointcutExpression.atAnnotation("A").evaluateRuntimeCondition("c", "m", null, null, null));
    assertTrue(PointcutExpression.handler("java.lang.Exception")
        .evaluateRuntimeCondition("c", "m", null, null, null));
  }

  @Test
  void evaluateRuntimeCondition_andOrNotDelegateToOperands() {
    PointcutExpression trueIf = PointcutExpression.ifCondition("args != null");
    PointcutExpression falseIf = PointcutExpression.ifCondition("args == null");
    Object[] args = new Object[] {"x"};

    assertFalse(
        PointcutExpression.and(trueIf, falseIf).evaluateRuntimeCondition("c", "m", args, null, null));
    assertTrue(
        PointcutExpression.or(trueIf, falseIf).evaluateRuntimeCondition("c", "m", args, null, null));
    assertFalse(PointcutExpression.not(trueIf).evaluateRuntimeCondition("c", "m", args, null, null));
    assertTrue(PointcutExpression.not(falseIf).evaluateRuntimeCondition("c", "m", args, null, null));
  }

  @Test
  void evaluateIf_argsLengthComparisons() {
    Object[] twoArgs = new Object[] {"a", "b"};

    assertFalse(
        PointcutExpression.ifCondition("args.length > 2")
            .evaluateRuntimeCondition("c", "m", twoArgs, null, null));
    assertTrue(
        PointcutExpression.ifCondition("args.length > 0")
            .evaluateRuntimeCondition("c", "m", twoArgs, null, null));
    assertTrue(
        PointcutExpression.ifCondition("args.length >= 2")
            .evaluateRuntimeCondition("c", "m", twoArgs, null, null));
    assertFalse(
        PointcutExpression.ifCondition("args.length >= 3")
            .evaluateRuntimeCondition("c", "m", twoArgs, null, null));

    // malformed thresholds fall into the NumberFormatException branches
    assertFalse(
        PointcutExpression.ifCondition("args.length > abc")
            .evaluateRuntimeCondition("c", "m", twoArgs, null, null));
    assertFalse(
        PointcutExpression.ifCondition("args.length >= abc")
            .evaluateRuntimeCondition("c", "m", twoArgs, null, null));

    // null arguments array
    assertFalse(
        PointcutExpression.ifCondition("args.length > 0")
            .evaluateRuntimeCondition("c", "m", null, null, null));
  }

  @Test
  void evaluateIf_argsNullChecks() {
    assertTrue(
        PointcutExpression.ifCondition("args != null")
            .evaluateRuntimeCondition("c", "m", new Object[0], null, null));
    assertFalse(
        PointcutExpression.ifCondition("args != null").evaluateRuntimeCondition("c", "m", null, null, null));
    assertTrue(
        PointcutExpression.ifCondition("args == null").evaluateRuntimeCondition("c", "m", null, null, null));
    assertFalse(
        PointcutExpression.ifCondition("args == null")
            .evaluateRuntimeCondition("c", "m", new Object[0], null, null));
  }

  @Test
  void evaluateIf_argIndexNullCheck() {
    Object[] args = new Object[] {"present", null};

    assertTrue(
        PointcutExpression.ifCondition("args[0] != null")
            .evaluateRuntimeCondition("c", "m", args, null, null));
    assertFalse(
        PointcutExpression.ifCondition("args[1] != null")
            .evaluateRuntimeCondition("c", "m", args, null, null));
    // out of range index
    assertFalse(
        PointcutExpression.ifCondition("args[5] != null")
            .evaluateRuntimeCondition("c", "m", args, null, null));
    // null arguments array with an index condition
    assertFalse(
        PointcutExpression.ifCondition("args[0] != null")
            .evaluateRuntimeCondition("c", "m", null, null, null));
    // non-integer index inside the brackets triggers the NumberFormatException branch
    assertFalse(
        PointcutExpression.ifCondition("args[x] != null")
            .evaluateRuntimeCondition("c", "m", args, null, null));
    // malformed (missing closing bracket) falls through to the "unknown condition" default
    assertTrue(
        PointcutExpression.ifCondition("args[0 != null")
            .evaluateRuntimeCondition("c", "m", args, null, null));
  }

  @Test
  void evaluateIf_resultAndExceptionConditions() {
    assertTrue(
        PointcutExpression.ifCondition("result != null")
            .evaluateRuntimeCondition("c", "m", null, "value", null));
    assertFalse(
        PointcutExpression.ifCondition("result != null")
            .evaluateRuntimeCondition("c", "m", null, null, null));
    assertTrue(
        PointcutExpression.ifCondition("return != null")
            .evaluateRuntimeCondition("c", "m", null, "value", null));
    assertTrue(
        PointcutExpression.ifCondition("exception != null")
            .evaluateRuntimeCondition("c", "m", null, null, new RuntimeException("boom")));
    assertFalse(
        PointcutExpression.ifCondition("exception != null")
            .evaluateRuntimeCondition("c", "m", null, null, null));
  }

  @Test
  void evaluateIf_nullOrEmptyOrUnknownConditionDefaultsToTrue() {
    assertTrue(PointcutExpression.ifCondition(null).evaluateRuntimeCondition("c", "m", null, null, null));
    assertTrue(PointcutExpression.ifCondition("").evaluateRuntimeCondition("c", "m", null, null, null));
    assertTrue(
        PointcutExpression.ifCondition("   ").evaluateRuntimeCondition("c", "m", null, null, null));
    assertTrue(
        PointcutExpression.ifCondition("some.unsupported.expression")
            .evaluateRuntimeCondition("c", "m", null, null, null));
  }

  @Test
  void evaluateCflow_nullInnerExpressionAlwaysMatchesEvenWithoutContext() {
    PointcutExpression cflow = PointcutExpression.cflow(null);
    assertNull(PointcutExpression.CflowContext.current());
    assertTrue(cflow.evaluateRuntimeCondition("pkg.Target", "target", null, null, null));
  }

  @Test
  void evaluateCflow_noActiveContextReturnsFalse() {
    PointcutExpression cflow =
        PointcutExpression.cflow(PointcutExpression.execution("*", "pkg.Caller", "call", ".."));
    assertNull(PointcutExpression.CflowContext.current());
    assertFalse(cflow.evaluateRuntimeCondition("pkg.Target", "target", null, null, null));
  }

  @Test
  void evaluateCflow_matchesCallerFrameExcludingTopFrame() {
    PointcutExpression cflow =
        PointcutExpression.cflow(PointcutExpression.execution("*", "pkg.Caller", "call", ".."));

    PointcutExpression.enterCflow("pkg.Caller", "call");
    PointcutExpression.enterCflow("pkg.Target", "target");
    try {
      assertTrue(cflow.evaluateRuntimeCondition("pkg.Target", "target", null, null, null));
    } finally {
      PointcutExpression.exitCflow("pkg.Target", "target");
      PointcutExpression.exitCflow("pkg.Caller", "call");
    }
  }

  @Test
  void evaluateCflow_doesNotMatchTopFrameItself() {
    // cflow(execution matching the CURRENT method) must be false: the current join point
    // itself is excluded, cflow only matches when some CALLER matches.
    PointcutExpression cflow =
        PointcutExpression.cflow(PointcutExpression.execution("*", "pkg.Target", "target", ".."));

    PointcutExpression.enterCflow("pkg.Target", "target");
    try {
      assertFalse(cflow.evaluateRuntimeCondition("pkg.Target", "target", null, null, null));
    } finally {
      PointcutExpression.exitCflow("pkg.Target", "target");
    }
  }

  @Test
  void evaluateCflow_noMatchInCallStackReturnsFalse() {
    PointcutExpression cflow =
        PointcutExpression.cflow(PointcutExpression.execution("*", "pkg.Unrelated", "other", ".."));

    PointcutExpression.enterCflow("pkg.Caller", "call");
    PointcutExpression.enterCflow("pkg.Target", "target");
    try {
      assertFalse(cflow.evaluateRuntimeCondition("pkg.Target", "target", null, null, null));
    } finally {
      PointcutExpression.exitCflow("pkg.Target", "target");
      PointcutExpression.exitCflow("pkg.Caller", "call");
    }
  }

  @Test
  void evaluateCflow_nestedCflowCallsTrackMultipleFrames() {
    PointcutExpression cflowOuter =
        PointcutExpression.cflow(PointcutExpression.execution("*", "pkg.Outer", "outer", ".."));
    PointcutExpression cflowMiddle =
        PointcutExpression.cflow(PointcutExpression.execution("*", "pkg.Middle", "middle", ".."));

    PointcutExpression.enterCflow("pkg.Outer", "outer");
    PointcutExpression.enterCflow("pkg.Middle", "middle");
    PointcutExpression.enterCflow("pkg.Inner", "inner");
    try {
      // From "Inner", both "Outer" and "Middle" are ancestor callers.
      assertTrue(cflowOuter.evaluateRuntimeCondition("pkg.Inner", "inner", null, null, null));
      assertTrue(cflowMiddle.evaluateRuntimeCondition("pkg.Inner", "inner", null, null, null));
    } finally {
      PointcutExpression.exitCflow("pkg.Inner", "inner");
    }

    // After popping "Inner", "Middle" is now the (excluded) top frame, but "Outer" is still
    // an ancestor caller.
    try {
      assertTrue(cflowOuter.evaluateRuntimeCondition("pkg.Middle", "middle", null, null, null));
      assertFalse(cflowMiddle.evaluateRuntimeCondition("pkg.Middle", "middle", null, null, null));
    } finally {
      PointcutExpression.exitCflow("pkg.Middle", "middle");
      PointcutExpression.exitCflow("pkg.Outer", "outer");
    }
  }

  @Test
  void cflowContext_threadLocalStateClearsWhenStackEmpties() {
    assertNull(PointcutExpression.CflowContext.current());
    PointcutExpression.enterCflow("pkg.A", "m");
    assertNotNull(PointcutExpression.CflowContext.current());
    PointcutExpression.exitCflow("pkg.A", "m");
    assertNull(PointcutExpression.CflowContext.current());

    // Exiting with an already-empty stack must be a safe no-op, not throw.
    assertDoesNotThrow(() -> PointcutExpression.exitCflow("pkg.A", "m"));
  }

  @Test
  void cflowContext_isIsolatedPerThread() throws InterruptedException {
    PointcutExpression.enterCflow("pkg.MainThread", "m");
    try {
      final boolean[] otherThreadSawContext = {true};
      Thread other =
          new Thread(
              () -> otherThreadSawContext[0] = PointcutExpression.CflowContext.current() != null);
      other.start();
      other.join();

      assertFalse(otherThreadSawContext[0], "cflow state must not leak across threads");
      assertNotNull(PointcutExpression.CflowContext.current(), "main thread's own state must remain");
    } finally {
      PointcutExpression.exitCflow("pkg.MainThread", "m");
    }
  }

  @Test
  void cflowContext_matchesConsidersEveryFrameIncludingTop() {
    PointcutExpression inner = PointcutExpression.execution("*", "pkg.Target", "target", "..");

    PointcutExpression.enterCflow("pkg.Caller", "call");
    PointcutExpression.enterCflow("pkg.Target", "target");
    try {
      PointcutExpression.CflowContext ctx = PointcutExpression.CflowContext.current();
      assertNotNull(ctx);
      // Unlike matchesExcludingTop, matches() considers the top-of-stack frame too.
      assertTrue(ctx.matches(inner, "pkg.Target", "target"));

      PointcutExpression unrelated =
          PointcutExpression.execution("*", "pkg.Nobody", "nothing", "..");
      assertFalse(ctx.matches(unrelated, "pkg.Target", "target"));
    } finally {
      PointcutExpression.exitCflow("pkg.Target", "target");
      PointcutExpression.exitCflow("pkg.Caller", "call");
    }
  }
}
