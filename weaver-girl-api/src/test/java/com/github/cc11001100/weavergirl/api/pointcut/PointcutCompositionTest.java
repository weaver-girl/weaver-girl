package com.github.cc11001100.weavergirl.api.pointcut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.Test;

class PointcutCompositionTest {

  @Test
  void and_bothMatch_returnsTrue() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    assertTrue(p1.and(p2).matches("com.example.Service", "process"));
  }

  @Test
  void and_oneFails_returnsFalse() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Other"), MethodMatcher.byName("run"));
    assertFalse(p1.and(p2).matches("com.example.Service", "process"));
  }

  @Test
  void or_eitherMatches_returnsTrue() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Other"), MethodMatcher.byName("run"));
    assertTrue(p1.or(p2).matches("com.example.Service", "process"));
    assertTrue(p1.or(p2).matches("com.example.Other", "run"));
  }

  @Test
  void or_neitherMatches_returnsFalse() {
    Pointcut p1 = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut p2 = new Pointcut(ClassMatcher.byName("com.example.Other"), MethodMatcher.byName("run"));
    assertFalse(p1.or(p2).matches("com.example.Unknown", "save"));
  }

  @Test
  void negate_invertsMatch() {
    Pointcut p = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    assertTrue(p.negate().matches("com.example.Other", "run"));
    assertFalse(p.negate().matches("com.example.Service", "process"));
  }

  @Test
  void composedPointcuts_preserveMatcherTypes() {
    Pointcut anyMethod = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.any());
    Pointcut specific = new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));

    Pointcut combined = anyMethod.and(specific);
    assertEquals(MethodMatcher.MatchType.EXACT_NAME, combined.getMethodMatcher().getMatchType());
  }

  // --- composed pointcuts must retain and evaluate cflow()/if() runtime conditions ---

  @Test
  void and_retainsRuntimeCondition_bothSidesMustMatch() {
    // static: class+method match; runtime: args != null (if condition on the second operand)
    Pointcut staticPc =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    Pointcut ifPc =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"),
            PointcutExpression.ifCondition("args != null"));

    Pointcut combined = staticPc.and(ifPc);
    // Static-only matches() ignores runtime conditions (unchanged contract)
    assertTrue(combined.matches("com.example.Service", "process"));
    // matchRuntime() evaluates them: null args fails the if() guard
    assertFalse(
        combined.matchRuntime("com.example.Service", "process", null, null, null, null),
        "and() must retain + evaluate the if() runtime condition of its operand");
  }

  @Test
  void or_keepsOperandRuntimeConditionsIndependent() {
    // OR decomposition must preserve (m1 && r1) || (m2 && r2) — each operand keeps its own
    // condition. A naive (m1 || m2) && (r1 || r2) lift would make the OR fail here, because
    // the static-only operand's (implicit true) condition would conflict with the guarded one.
    Pointcut ifPc =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"),
            PointcutExpression.ifCondition("args != null"));
    Pointcut otherStatic =
        new Pointcut(ClassMatcher.byName("com.example.Other"), MethodMatcher.byName("run"));

    Pointcut combined = ifPc.or(otherStatic);
    // Left operand guarded: null args → left fails → full OR depends on the right operand's
    // static match. Right operand has no runtime condition, so it matches statically.
    assertTrue(
        combined.matchRuntime("com.example.Other", "run", null, null, null, null),
        "or() right (static-only) operand must still match when the guarded left operand fails on its own runtime condition");
  }

  @Test
  void negate_invertsStaticAndRuntime() {
    Pointcut ifPc =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"),
            PointcutExpression.ifCondition("args != null"));
    Object[] args = new Object[] {"x"};

    assertFalse(
        ifPc.negate().matchRuntime("com.example.Service", "process", null, args, null, null),
        "negate() of a satisfied (static+if) pointcut must be false");
    // null args: the guarded operand fails its runtime condition → negated → true
    assertTrue(
        ifPc.negate().matchRuntime("com.example.Service", "process", null, null, null, null),
        "negate() must invert the runtime condition too, not just the static matchers");
  }

  @Test
  void basePointcut_matchRuntime_noExpressionMatchesWhenStaticMatches() {
    Pointcut plain =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process"));
    assertTrue(
        plain.matchRuntime("com.example.Service", "process", null, new Object[] {"x"}, null, null));
    // Class matching is NOT re-checked at dispatch time — the core engine already selected the
    // woven class. Only the method matcher is evaluated (identical to the pre-matchRuntime
    // dispatch path), so an unrelated class name with a matching method still matches.
    assertTrue(
        plain.matchRuntime("com.example.Other", "process", null, new Object[] {"x"}, null, null),
        "matchRuntime() must not re-check the class matcher at dispatch time");
    assertFalse(
        plain.matchRuntime("com.example.Service", "otherMethod", null, new Object[] {"x"}, null, null),
        "a non-matching method name must fail matchRuntime()");
  }

  @Test
  void and_matchRuntime_parameterTypesForSignatureMatching() {
    Pointcut sig =
        new Pointcut(
            ClassMatcher.byName("com.example.Service"),
            MethodMatcher.bySignature("process", "java.lang.String"));
    Pointcut ifPc =
        new Pointcut(ClassMatcher.any(), MethodMatcher.any(),
            PointcutExpression.ifCondition("args != null"));
    Pointcut combined = sig.and(ifPc);

    assertTrue(
        combined.matchRuntime(
            "com.example.Service",
            "process",
            new Class<?>[] {String.class},
            new Object[] {"x"},
            null,
            null),
        "and() must pass parameterTypes through for signature matching");
    assertFalse(
        combined.matchRuntime(
            "com.example.Service", "process", new Class<?>[] {Integer.class}, new Object[] {"x"},
            null, null),
        "signature mismatch must fail the composed pointcut");
  }
}
