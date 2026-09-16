package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.ValidateArgs;
import com.github.cc11001100.weavergirl.annotation.ValidateReturn;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code @ValidateArgs}/{@code @ValidateReturn} wrapper methods and their private rule
 * evaluation helpers ({@code evaluateRule}, {@code evaluateReturnRule}, {@code evaluateCondition})
 * in {@link AnnotationPluginLoader}, driven end-to-end through the real interceptor {@code
 * before()}/{@code after()} callbacks.
 */
class ValidationWrapperCoverageTest {

  @WeaveClass(target = "com.example.ValidateArgsService")
  public static class ValidateArgsInterceptor {

    @ValidateArgs(value = "notNullCheck", rules = {"arg[0] != null"}, message = "arg0 required")
    public void notNullCheck(MethodInvocation inv) {}

    @ValidateArgs(value = "nullCheck", rules = {"arg[0] == null"}, message = "arg0 must be null")
    public void nullCheck(MethodInvocation inv) {}

    @ValidateArgs(value = "gtCheck", rules = {"arg[0] > 0"}, message = "arg0 must be positive")
    public void gtCheck(MethodInvocation inv) {}

    @ValidateArgs(value = "ltCheck", rules = {"arg[0] < 10"}, message = "arg0 must be < 10")
    public void ltCheck(MethodInvocation inv) {}

    @ValidateArgs(value = "gteCheck", rules = {"arg[0] >= 5"}, message = "arg0 must be >= 5")
    public void gteCheck(MethodInvocation inv) {}

    @ValidateArgs(value = "lteCheck", rules = {"arg[0] <= 5"}, message = "arg0 must be <= 5")
    public void lteCheck(MethodInvocation inv) {}

    @ValidateArgs(value = "noArgRule", rules = {""}, message = "unused")
    public void noArgRule(MethodInvocation inv) {}

    @ValidateArgs(value = "fallbackNonNumber", rules = {"arg[0] > 5"}, message = "unused")
    public void fallbackNonNumber(MethodInvocation inv) {}

    @ValidateArgs(value = "fallbackUnknownCondition", rules = {"arg[0] weird"}, message = "unused")
    public void fallbackUnknownCondition(MethodInvocation inv) {}

    @ValidateArgs(
        value = "shortCircuit",
        rules = {"arg[0] == null", "arg[1] > abc"},
        message = "short circuit failed")
    public void shortCircuit(MethodInvocation inv) {}
  }

  @WeaveClass(target = "com.example.ValidateReturnService")
  public static class ValidateReturnInterceptor {

    @ValidateReturn(
        value = "retNotNullCheck",
        rules = {"result != null"},
        message = "result required")
    public void retNotNullCheck(MethodInvocation inv) {}

    @ValidateReturn(
        value = "retNullCheck",
        rules = {"result == null"},
        message = "result must be null")
    public void retNullCheck(MethodInvocation inv) {}

    @ValidateReturn(
        value = "retGtCheck",
        rules = {"result > 0"},
        message = "result must be positive")
    public void retGtCheck(MethodInvocation inv) {}

    @ValidateReturn(
        value = "retLtCheck", rules = {"result < 10"}, message = "result must be < 10")
    public void retLtCheck(MethodInvocation inv) {}

    @ValidateReturn(
        value = "retGteCheck", rules = {"result >= 5"}, message = "result must be >= 5")
    public void retGteCheck(MethodInvocation inv) {}

    @ValidateReturn(
        value = "retLteCheck", rules = {"result <= 5"}, message = "result must be <= 5")
    public void retLteCheck(MethodInvocation inv) {}

    @ValidateReturn(value = "retNoResultRule", rules = {""}, message = "unused")
    public void retNoResultRule(MethodInvocation inv) {}

    @ValidateReturn(
        value = "retShortCircuit",
        rules = {"result == null", "result > abc"},
        message = "short circuit failed")
    public void retShortCircuit(MethodInvocation inv) {}
  }

  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    registry = new DefaultInterceptorRegistry();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(ValidateArgsInterceptor.class);
    classes.add(ValidateReturnInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor interceptorFor(String methodName) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + methodName))
        .getInterceptor();
  }

  private MethodInvocation invocationWithArgs(String methodName, Object... args) {
    return new MethodInvocation(ValidateArgsInterceptor.class, methodName, "target", args);
  }

  // ==================== wrapWithValidateArgs / evaluateRule ====================

  @Test
  void notNullCheck_validArgument_doesNotFail() {
    Interceptor interceptor = interceptorFor("notNullCheck");
    MethodInvocation inv = invocationWithArgs("notNullCheck", "value");
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
    assertNull(inv.getAttachment("validateArgs.failed"));
  }

  @Test
  void notNullCheck_nullArgument_failsAndSkips() {
    Interceptor interceptor = interceptorFor("notNullCheck");
    MethodInvocation inv = invocationWithArgs("notNullCheck", new Object[] {null});
    interceptor.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("arg0 required", inv.getAttachment("validateArgs.failed"));
  }

  @Test
  void nullCheck_nullArgument_doesNotFail() {
    Interceptor interceptor = interceptorFor("nullCheck");
    MethodInvocation inv = invocationWithArgs("nullCheck", new Object[] {null});
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void nullCheck_nonNullArgument_failsAndSkips() {
    Interceptor interceptor = interceptorFor("nullCheck");
    MethodInvocation inv = invocationWithArgs("nullCheck", "not null");
    interceptor.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("arg0 must be null", inv.getAttachment("validateArgs.failed"));
  }

  @Test
  void gtCheck_satisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("gtCheck");
    MethodInvocation inv = invocationWithArgs("gtCheck", 5);
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void gtCheck_notSatisfied_failsAndSkips() {
    Interceptor interceptor = interceptorFor("gtCheck");
    MethodInvocation inv = invocationWithArgs("gtCheck", -5);
    interceptor.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("arg0 must be positive", inv.getAttachment("validateArgs.failed"));
  }

  @Test
  void ltCheck_satisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("ltCheck");
    MethodInvocation inv = invocationWithArgs("ltCheck", 5);
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void ltCheck_notSatisfied_failsAndSkips() {
    Interceptor interceptor = interceptorFor("ltCheck");
    MethodInvocation inv = invocationWithArgs("ltCheck", 20);
    interceptor.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("arg0 must be < 10", inv.getAttachment("validateArgs.failed"));
  }

  @Test
  void gteCheck_satisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("gteCheck");
    MethodInvocation inv = invocationWithArgs("gteCheck", 5);
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void gteCheck_notSatisfied_failsAndSkips() {
    Interceptor interceptor = interceptorFor("gteCheck");
    MethodInvocation inv = invocationWithArgs("gteCheck", 4);
    interceptor.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("arg0 must be >= 5", inv.getAttachment("validateArgs.failed"));
  }

  @Test
  void lteCheck_satisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("lteCheck");
    MethodInvocation inv = invocationWithArgs("lteCheck", 5);
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void lteCheck_notSatisfied_failsAndSkips() {
    Interceptor interceptor = interceptorFor("lteCheck");
    MethodInvocation inv = invocationWithArgs("lteCheck", 6);
    interceptor.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("arg0 must be <= 5", inv.getAttachment("validateArgs.failed"));
  }

  @Test
  void ruleWithoutArgBracket_isAlwaysValid() {
    // evaluateRule's false branch: a rule not containing "arg[" returns true unconditionally.
    Interceptor interceptor = interceptorFor("noArgRule");
    MethodInvocation inv = invocationWithArgs("noArgRule", new Object[] {null});
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void evaluateCondition_fallsBackToTrue_whenValueIsNotANumber() {
    // ">" against a non-Number value fails the "instanceof Number" guard on every numeric branch,
    // so evaluateCondition falls through to its final "return true".
    Interceptor interceptor = interceptorFor("fallbackNonNumber");
    MethodInvocation inv = invocationWithArgs("fallbackNonNumber", "hello");
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void evaluateCondition_fallsBackToTrue_forUnrecognizedCondition() {
    Interceptor interceptor = interceptorFor("fallbackUnknownCondition");
    MethodInvocation inv = invocationWithArgs("fallbackUnknownCondition", 123);
    interceptor.before(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void validateArgs_shortCircuits_onFirstFailingRule() {
    // Only one argument is supplied; if the second rule ("arg[1] > abc") were evaluated after the
    // first rule already failed, inv.getArgument(1) would throw IndexOutOfBoundsException. The
    // absence of any exception proves the loop's early "return" short-circuits correctly.
    Interceptor interceptor = interceptorFor("shortCircuit");
    MethodInvocation inv = invocationWithArgs("shortCircuit", "not null");
    assertDoesNotThrow(() -> interceptor.before(inv));
    assertTrue(inv.isSkipped());
    assertEquals("short circuit failed", inv.getAttachment("validateArgs.failed"));
  }

  // ==================== wrapWithValidateReturn / evaluateReturnRule ====================

  private MethodInvocation invocationWithReturn(String methodName, Object returnValue) {
    MethodInvocation inv =
        new MethodInvocation(ValidateReturnInterceptor.class, methodName, "target", new Object[0]);
    inv.initReturnValue(returnValue);
    return inv;
  }

  @Test
  void notNullCheck_validReturn_doesNotFail() {
    Interceptor interceptor = interceptorFor("retNotNullCheck");
    MethodInvocation inv = invocationWithReturn("retNotNullCheck", "value");
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void notNullCheck_nullReturn_fails() {
    Interceptor interceptor = interceptorFor("retNotNullCheck");
    MethodInvocation inv = invocationWithReturn("retNotNullCheck", null);
    interceptor.after(inv);
    assertEquals("result required", inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void nullCheck_nullReturn_doesNotFail() {
    Interceptor interceptor = interceptorFor("retNullCheck");
    MethodInvocation inv = invocationWithReturn("retNullCheck", null);
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void nullCheck_nonNullReturn_fails() {
    Interceptor interceptor = interceptorFor("retNullCheck");
    MethodInvocation inv = invocationWithReturn("retNullCheck", "not null");
    interceptor.after(inv);
    assertEquals("result must be null", inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void gtCheck_returnSatisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("retGtCheck");
    MethodInvocation inv = invocationWithReturn("retGtCheck", 5);
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void gtCheck_returnNotSatisfied_fails() {
    Interceptor interceptor = interceptorFor("retGtCheck");
    MethodInvocation inv = invocationWithReturn("retGtCheck", -5);
    interceptor.after(inv);
    assertEquals("result must be positive", inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void ltCheck_returnSatisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("retLtCheck");
    MethodInvocation inv = invocationWithReturn("retLtCheck", 5);
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void ltCheck_returnNotSatisfied_fails() {
    Interceptor interceptor = interceptorFor("retLtCheck");
    MethodInvocation inv = invocationWithReturn("retLtCheck", 20);
    interceptor.after(inv);
    assertEquals("result must be < 10", inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void gteCheck_returnSatisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("retGteCheck");
    MethodInvocation inv = invocationWithReturn("retGteCheck", 5);
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void gteCheck_returnNotSatisfied_fails() {
    Interceptor interceptor = interceptorFor("retGteCheck");
    MethodInvocation inv = invocationWithReturn("retGteCheck", 4);
    interceptor.after(inv);
    assertEquals("result must be >= 5", inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void lteCheck_returnSatisfied_doesNotFail() {
    Interceptor interceptor = interceptorFor("retLteCheck");
    MethodInvocation inv = invocationWithReturn("retLteCheck", 5);
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void lteCheck_returnNotSatisfied_fails() {
    Interceptor interceptor = interceptorFor("retLteCheck");
    MethodInvocation inv = invocationWithReturn("retLteCheck", 6);
    interceptor.after(inv);
    assertEquals("result must be <= 5", inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void returnRuleWithoutResultPrefix_isAlwaysValid() {
    // evaluateReturnRule's false branch: a rule not starting with "result" returns true
    // unconditionally.
    Interceptor interceptor = interceptorFor("retNoResultRule");
    MethodInvocation inv = invocationWithReturn("retNoResultRule", null);
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void validateReturn_shortCircuits_onFirstFailingRule() {
    // The return value is a Number, so if the second rule ("result > abc") were evaluated after
    // the first rule already failed, Double.parseDouble("abc") would throw
    // NumberFormatException. The absence of any exception proves the loop's early "return"
    // short-circuits correctly.
    Interceptor interceptor = interceptorFor("retShortCircuit");
    MethodInvocation inv = invocationWithReturn("retShortCircuit", 42);
    assertDoesNotThrow(() -> interceptor.after(inv));
    assertEquals("short circuit failed", inv.getAttachment("validateReturn.failed"));
  }
}
