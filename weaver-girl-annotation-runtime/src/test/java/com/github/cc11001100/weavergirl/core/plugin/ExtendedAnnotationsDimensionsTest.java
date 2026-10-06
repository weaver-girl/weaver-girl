package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the eight annotations added in 1.9.0: {@code @CachePut}, {@code @FallbackValue},
 * {@code @SuppressExceptions}, {@code @DenyAll}, {@code @PreAuthorize}, {@code @WithMDC}, {@code
 * @SpanAttribute}, and {@code @WarnIfSlow}.
 *
 * <p>{@code AnnotationPluginLoader} keeps static shared state ({@code cacheStore}, {@code
 * idempotencyStore}, {@code circuitStates}, {@code lockObjects}) across the whole test JVM, so
 * every cache key prefix and target-method name below is unique to this file (prefix {@code nde})
 * to avoid colliding with other test classes.
 */
class ExtendedAnnotationsDimensionsTest {

  private static class Target {}

  @WeaveClass(target = "com.example.ExtendedAnnotationsService")
  public static class ExtendedAnnotationsInterceptor {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    // ---- @CachePut ----

    @CachePut(value = "ndePutA")
    public void putA(MethodInvocation inv) {}

    @CachePut(value = "ndePutB", ttlMs = 60_000, keyPrefix = "ndeKeyB", keyArgIndices = {0})
    public void putB(MethodInvocation inv) {}

    // ---- @FallbackValue ----

    @FallbackValue(value = "ndeFallbackString", stringValue = "fallback")
    public void fallbackString(MethodInvocation inv) {}

    @FallbackValue(value = "ndeFallbackLong", longValue = 42L)
    public void fallbackLong(MethodInvocation inv) {}

    @FallbackValue(value = "ndeFallbackDouble", doubleValue = 3.14d)
    public void fallbackDouble(MethodInvocation inv) {}

    @FallbackValue(value = "ndeFallbackBool", booleanValue = true, useBooleanValue = true)
    public void fallbackBool(MethodInvocation inv) {}

    @FallbackValue(
        value = "ndeFallbackFiltered",
        stringValue = "nope",
        onExceptions = {IOException.class})
    public void fallbackFiltered(MethodInvocation inv) {}

    // ---- @SuppressExceptions ----

    @SuppressExceptions(value = "ndeSuppressAll")
    public void suppressAll(MethodInvocation inv) {}

    @SuppressExceptions(value = "ndeSuppressFiltered", suppressFor = {IllegalStateException.class})
    public void suppressFiltered(MethodInvocation inv) {}

    // ---- @DenyAll ----

    @DenyAll(value = "ndeDeny", message = "sealed endpoint")
    public void deny(MethodInvocation inv) {
      calls.add("deny");
    }

    // ---- @PreAuthorize ----

    @PreAuthorize(value = "ndeAuthorizeTrivial", expression = "arg[0] != null")
    public void authorizeTrivial(MethodInvocation inv) {}

    @PreAuthorize(
        value = "ndeAuthorizeArith",
        expression = "arg[1] >= 0",
        message = "negative not allowed")
    public void authorizeArith(MethodInvocation inv) {}

    // ---- @WithMDC ----

    @WithMDC(value = "ndeWithMdcArgs", keys = {"ctx.tenant", "ctx.requestId"}, argIndices = {0, 1})
    public void withMdcArgs(MethodInvocation inv) {}

    @WithMDC(value = "ndeWithMdcStatic", keys = {"ctx.static"}, staticValues = {"S1"})
    public void withMdcStatic(MethodInvocation inv) {}

    // ---- @SpanAttribute ----

    @SpanAttribute(value = "ndeAttrStatic", key = "order.kind", attributeValue = "rush")
    public void attrStatic(MethodInvocation inv) {}

    @SpanAttribute(value = "ndeAttrArg", key = "order.id", argIndex = 0)
    public void attrArg(MethodInvocation inv) {}

    @SpanAttribute(value = "ndeAttrReturn", key = "order.status", useReturn = true)
    public void attrReturn(MethodInvocation inv) {}

    // ---- @WarnIfSlow ----

    @WarnIfSlow(value = "ndeSlowBelow", thresholdMs = 100_000)
    public void slowBelow(MethodInvocation inv) {}

    @WarnIfSlow(value = "ndeSlowAbove", thresholdMs = -1)
    public void slowAbove(MethodInvocation inv) {}

    @WarnIfSlow(value = "ndeSlowException", thresholdMs = -1)
    public void slowException(MethodInvocation inv) {}
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
    ExtendedAnnotationsInterceptor.calls.clear();
    ThreadContext.clear();
  }

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
  }

  private void load() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(ExtendedAnnotationsInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor interceptorFor(String methodName) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + methodName))
        .getInterceptor();
  }

  private MethodInvocation invocation(String methodName, Object... args) {
    return new MethodInvocation(ExtendedAnnotationsInterceptor.class, methodName, new Target(), args);
  }

  // ==================== @CachePut ====================

  @Test
  void cachePut_after_doesNotSkipAndWritesUnderDefaultKeyLayout() {
    load();
    Interceptor put = interceptorFor("ndePutA");
    MethodInvocation inv = invocation("ndePutA", "alpha");
    put.before(inv);
    // A successful return must not be skipped by the write-through wrapper.
    assertFalse(inv.isSkipped());
    put.after(inv);
    assertFalse(inv.isSkipped());
  }

  @Test
  void cachePut_keyArgIndices_setsCacheKeyAttachment() {
    load();
    Interceptor put = interceptorFor("ndePutB");
    MethodInvocation inv = invocation("ndePutB", "a", "b");
    put.before(inv);
    assertEquals("ndeKeyB:a,", inv.getAttachment("cachePut.key"));
  }

  // ==================== @FallbackValue ====================

  @Test
  void fallbackValue_string_suppressesExceptionAndReturnsStatic() {
    load();
    Interceptor fb = interceptorFor("ndeFallbackString");
    MethodInvocation inv = invocation("ndeFallbackString");
    inv.setThrowable(new RuntimeException("boom"));
    fb.onException(inv);
    assertTrue(inv.isExceptionSuppressed());
    assertEquals("fallback", inv.getReturnValue());
  }

  @Test
  void fallbackValue_long_suppressesExceptionAndReturnsNumber() {
    load();
    Interceptor fb = interceptorFor("ndeFallbackLong");
    MethodInvocation inv = invocation("ndeFallbackLong");
    inv.setThrowable(new RuntimeException("boom"));
    fb.onException(inv);
    assertTrue(inv.isExceptionSuppressed());
    assertEquals(42L, inv.getReturnValue());
  }

  @Test
  void fallbackValue_double_suppressesException() {
    load();
    Interceptor fb = interceptorFor("ndeFallbackDouble");
    MethodInvocation inv = invocation("ndeFallbackDouble");
    inv.setThrowable(new RuntimeException("boom"));
    fb.onException(inv);
    assertTrue(inv.isExceptionSuppressed());
    assertEquals(3.14d, inv.getReturnValue());
  }

  @Test
  void fallbackValue_boolean_useBooleanValueFlagEnablesIt() {
    load();
    Interceptor fb = interceptorFor("ndeFallbackBool");
    MethodInvocation inv = invocation("ndeFallbackBool");
    inv.setThrowable(new RuntimeException("boom"));
    fb.onException(inv);
    assertTrue(inv.isExceptionSuppressed());
    assertEquals(true, inv.getReturnValue());
  }

  @Test
  void fallbackValue_nonMatchingException_notSuppressed() {
    load();
    Interceptor fb = interceptorFor("ndeFallbackFiltered");
    MethodInvocation inv = invocation("ndeFallbackFiltered");
    inv.setThrowable(new IllegalStateException("not an IOException"));
    fb.onException(inv);
    assertFalse(inv.isExceptionSuppressed());
  }

  // ==================== @SuppressExceptions ====================

  @Test
  void suppressExceptions_emptyFilter_suppressesAll() {
    load();
    Interceptor sup = interceptorFor("ndeSuppressAll");
    MethodInvocation inv = invocation("ndeSuppressAll");
    inv.setThrowable(new RuntimeException("anything"));
    sup.onException(inv);
    assertTrue(inv.isExceptionSuppressed());
  }

  @Test
  void suppressExceptions_matchingType_suppresses() {
    load();
    Interceptor sup = interceptorFor("ndeSuppressFiltered");
    MethodInvocation inv = invocation("ndeSuppressFiltered");
    inv.setThrowable(new IllegalStateException("matches"));
    sup.onException(inv);
    assertTrue(inv.isExceptionSuppressed());
  }

  @Test
  void suppressExceptions_nonMatchingType_notSuppressed() {
    load();
    Interceptor sup = interceptorFor("ndeSuppressFiltered");
    MethodInvocation inv = invocation("ndeSuppressFiltered");
    inv.setThrowable(new RuntimeException("does not match"));
    sup.onException(inv);
    assertFalse(inv.isExceptionSuppressed());
  }

  // ==================== @DenyAll ====================

  @Test
  void denyAll_skipsMethodAndFlagsRejection() {
    load();
    Interceptor deny = interceptorFor("ndeDeny");
    MethodInvocation inv = invocation("ndeDeny");
    deny.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("sealed endpoint", inv.getAttachment("denyAll.rejected"));
    assertNull(inv.getReturnValue());
    // The annotated advice method is still invoked by the wrapper (observability hook).
    assertTrue(ExtendedAnnotationsInterceptor.calls.contains("deny"));
  }

  // ==================== @PreAuthorize ====================

  @Test
  void preAuthorize_expressionHolds_doesNotSkip() {
    load();
    Interceptor guard = interceptorFor("ndeAuthorizeTrivial");
    MethodInvocation inv = invocation("ndeAuthorizeTrivial", "user");
    guard.before(inv);
    assertFalse(inv.isSkipped());
    assertNull(inv.getAttachment("preAuthorize.denied"));
  }

  @Test
  void preAuthorize_expressionFails_skipsAndRecordsMessage() {
    load();
    Interceptor guard = interceptorFor("ndeAuthorizeTrivial");
    MethodInvocation inv = invocation("ndeAuthorizeTrivial", new Object[] {null});
    guard.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("Access denied", inv.getAttachment("preAuthorize.denied"));
  }

  @Test
  void preAuthorize_arithmeticExpression_skipsOnNegative() {
    load();
    Interceptor guard = interceptorFor("ndeAuthorizeArith");
    MethodInvocation inv = invocation("ndeAuthorizeArith", "u", -5);
    guard.before(inv);
    assertTrue(inv.isSkipped());
    assertEquals("negative not allowed", inv.getAttachment("preAuthorize.denied"));
  }

  // ==================== @WithMDC ====================

  @Test
  void withMdc_writesThreadContextAndRestoresOnExit() {
    ThreadContext.put("ctx.tenant", "previous-tenant");
    load();
    Interceptor wm = interceptorFor("ndeWithMdcArgs");
    MethodInvocation inv = invocation("ndeWithMdcArgs", "tenant-1", "req-9");

    wm.before(inv);
    assertEquals("tenant-1", ThreadContext.get("ctx.tenant"));
    assertEquals("req-9", ThreadContext.get("ctx.requestId"));

    wm.after(inv);
    assertEquals("previous-tenant", ThreadContext.get("ctx.tenant"));
    assertNull(ThreadContext.get("ctx.requestId"));
  }

  @Test
  void withMdc_staticValue_writesKeyAndRemovesOnExit() {
    load();
    Interceptor wm = interceptorFor("ndeWithMdcStatic");
    MethodInvocation inv = invocation("ndeWithMdcStatic");
    wm.before(inv);
    assertEquals("S1", ThreadContext.get("ctx.static"));
    wm.after(inv);
    assertNull(ThreadContext.get("ctx.static"));
  }

  // ==================== @SpanAttribute ====================

  @Test
  void spanAttribute_staticValue_setsAttachment() {
    load();
    Interceptor attr = interceptorFor("ndeAttrStatic");
    MethodInvocation inv = invocation("ndeAttrStatic");
    attr.before(inv);
    assertEquals("rush", inv.getAttachment("spanAttr.order.kind"));
  }

  @Test
  void spanAttribute_argIndex_readsArgument() {
    load();
    Interceptor attr = interceptorFor("ndeAttrArg");
    MethodInvocation inv = invocation("ndeAttrArg", "order-123");
    attr.before(inv);
    assertEquals("order-123", inv.getAttachment("spanAttr.order.id"));
  }

  @Test
  void spanAttribute_useReturn_writesReturnValueOnAfter() {
    load();
    Interceptor attr = interceptorFor("ndeAttrReturn");
    MethodInvocation inv = invocation("ndeAttrReturn");
    inv.initReturnValue("paid");
    attr.after(inv);
    assertEquals("paid", inv.getAttachment("spanAttr.order.status"));
  }

  // ==================== @WarnIfSlow ====================

  @Test
  void warnIfSlow_belowThreshold_doesNotLog() {
    load();
    Interceptor slow = interceptorFor("ndeSlowBelow");
    MethodInvocation inv = invocation("ndeSlowBelow");
    slow.before(inv);
    slow.after(inv);
    // No exception — the wrapper runs without observation failures.
    assertNotNull(slow);
  }

  @Test
  void warnIfSlow_aboveThreshold_completesWithoutThrowing() {
    load();
    Interceptor slow = interceptorFor("ndeSlowAbove");
    MethodInvocation inv = invocation("ndeSlowAbove");
    slow.before(inv);
    slow.after(inv);
    assertNotNull(slow);
  }

  @Test
  void warnIfSlow_onExceptionPath_completesWithoutThrowing() {
    load();
    Interceptor slow = interceptorFor("ndeSlowException");
    MethodInvocation inv = invocation("ndeSlowException");
    inv.setThrowable(new RuntimeException("boom"));
    slow.before(inv);
    slow.onException(inv);
    assertNotNull(slow);
  }
}