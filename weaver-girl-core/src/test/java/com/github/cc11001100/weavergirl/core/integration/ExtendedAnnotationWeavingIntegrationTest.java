package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.CachePut;
import com.github.cc11001100.weavergirl.annotation.CacheResult;
import com.github.cc11001100.weavergirl.annotation.DenyAll;
import com.github.cc11001100.weavergirl.annotation.FallbackValue;
import com.github.cc11001100.weavergirl.annotation.PreAuthorize;
import com.github.cc11001100.weavergirl.annotation.SpanAttribute;
import com.github.cc11001100.weavergirl.annotation.SuppressExceptions;
import com.github.cc11001100.weavergirl.annotation.WarnIfSlow;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.annotation.WithMDC;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that the eight annotations added in 1.9.0 take real effect after bytecode
 * weaving: {@code @CachePut} + {@code @CacheResult}, {@code @FallbackValue}, {@code
 * @SuppressExceptions}, {@code @DenyAll}, {@code @PreAuthorize}, {@code @WithMDC}, {@code
 * @SpanAttribute}, and {@code @WarnIfSlow}.
 *
 * <p>Each test owns a distinct target class so transformer registrations from earlier tests never
 * collide. The {@code AnnotationPluginLoader} static {@code cacheStore} is shared across the JVM,
 * so the cache test uses the unique {@code eaiCache} key prefix.
 */
class ExtendedAnnotationWeavingIntegrationTest {

  // ==================== @CachePut + @CacheResult ====================

  public static class CacheTarget {
    public static volatile int storeBodyRuns = 0;
    public static volatile int loadBodyRuns = 0;

    public String store(String id) {
      storeBodyRuns++;
      return "saved-" + id;
    }

    public String load(String id) {
      loadBodyRuns++;
      return "computed-" + id;
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$CacheTarget")
  public static class CacheAdvice {
    @CachePut(value = "store", keyPrefix = "eaiCache", keyArgIndices = {0})
    public void storePut(MethodInvocation inv) {}

    @CacheResult(value = "load", keyPrefix = "eaiCache", keyArgIndices = {0})
    public void loadResult(MethodInvocation inv) {}
  }

  // ==================== @FallbackValue ====================

  public static class FallbackTarget {
    public String charge() {
      throw new IllegalStateException("card declined");
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$FallbackTarget")
  public static class FallbackAdvice {
    @FallbackValue(value = "charge", stringValue = "fallback-charge")
    public void chargeFallback(MethodInvocation inv) {}
  }

  // ==================== @SuppressExceptions ====================

  public static class SuppressTarget {
    public static volatile int bodyRuns = 0;

    public void submit() {
      bodyRuns++;
      throw new RuntimeException("backend unavailable");
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$SuppressTarget")
  public static class SuppressAdvice {
    @SuppressExceptions(value = "submit")
    public void swallow(MethodInvocation inv) {}
  }

  // ==================== @DenyAll ====================

  public static class DenyTarget {
    public static volatile boolean bodyRan = false;

    public String adminOp() {
      bodyRan = true;
      return "secret";
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$DenyTarget")
  public static class DenyAdvice {
    @DenyAll(value = "adminOp", message = "endpoint sealed")
    public void deny(MethodInvocation inv) {}
  }

  // ==================== @PreAuthorize ====================

  public static class PreAuthTarget {
    public static volatile int bodyRuns = 0;

    public String withdraw(int amount) {
      bodyRuns++;
      return "ok:" + amount;
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$PreAuthTarget")
  public static class PreAuthAdvice {
    @PreAuthorize(value = "withdraw", expression = "arg[0] >= 0", message = "negative not allowed")
    public void guard(MethodInvocation inv) {}
  }

  // ==================== @WithMDC ====================

  public static class MdcTarget {
    public static volatile String seenTenant;

    public void withContext(String tenant) {
      seenTenant = ThreadContext.get("eai.tenant");
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$MdcTarget")
  public static class MdcAdvice {
    @WithMDC(value = "withContext", keys = {"eai.tenant"}, argIndices = {0})
    public void context(MethodInvocation inv) {}
  }

  // ==================== @SpanAttribute ====================

  public static class SpanTarget {
    public String spanTarget(String id) {
      return "order:" + id;
    }

    public String spanTarget2() {
      return "paid";
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$SpanTarget")
  public static class SpanAdvice {
    static final List<String> captured = Collections.synchronizedList(new ArrayList<>());

    @SpanAttribute(value = "spanTarget", key = "order.id", argIndex = 0)
    public void idAttr(MethodInvocation inv) {}

    @SpanAttribute(value = "spanTarget2", key = "order.status", useReturn = true)
    public void statusAttr(MethodInvocation inv) {}

    @After("spanTarget")
    public void captureId(MethodInvocation inv) {
      captured.add("id:" + inv.getAttachment("spanAttr.order.id"));
    }

    @After("spanTarget2")
    public void captureStatus(MethodInvocation inv) {
      captured.add("status:" + inv.getAttachment("spanAttr.order.status"));
    }
  }

  // ==================== @WarnIfSlow ====================

  public static class SlowTarget {
    public String slowOp() {
      return "ok";
    }
  }

  @WeaveClass(
      target =
          "com.github.cc11001100.weavergirl.core.integration.ExtendedAnnotationWeavingIntegrationTest$SlowTarget")
  public static class SlowAdvice {
    @WarnIfSlow(value = "slowOp", thresholdMs = 100_000)
    public void slow(MethodInvocation inv) {}
  }

  // ==================== Test scaffolding ====================

  private static Instrumentation instrumentation;
  private DefaultInterceptorRegistry registry;

  @BeforeAll
  static void setUpClass() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Exception e) {
      instrumentation = null;
    }
  }

  @BeforeEach
  void setUp() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        instrumentation != null, "ByteBuddyAgent self-attach not available in this environment");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        instrumentation.isRetransformClassesSupported(),
        "JVM does not support class retransformation");
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    ThreadContext.clear();
    CacheTarget.storeBodyRuns = 0;
    CacheTarget.loadBodyRuns = 0;
    SuppressTarget.bodyRuns = 0;
    DenyTarget.bodyRan = false;
    PreAuthTarget.bodyRuns = 0;
    MdcTarget.seenTenant = null;
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    if (registry != null) {
      registry.clear();
    }
  }

  private void loadAndWeave(Class<?> adviceClass) {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(adviceClass);
    new AnnotationPluginLoader().loadAnnotatedInterceptors(classes, registry);
    assertFalse(registry.getAllDefinitions().isEmpty(), "advice should register definitions");
    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    // eagerRetransform=true: targets may already be loaded in this test JVM
    transformer.install(instrumentation, true);
  }

  // ==================== Tests ====================

  @Test
  void cachePut_writesThroughToSharedCacheAndCacheResultHits() {
    loadAndWeave(CacheAdvice.class);
    CacheTarget target = new CacheTarget();
    assertEquals("saved-1", target.store("1"));
    assertEquals(
        "saved-1", target.load("1"), "load must return the value written by @CachePut");
    assertEquals(1, CacheTarget.storeBodyRuns, "store body must run once");
    assertEquals(
        0, CacheTarget.loadBodyRuns, "load body must be skipped by the @CacheResult cache hit");
  }

  @Test
  void fallbackValue_swallowsRealExceptionAndReturnsStaticValue() {
    loadAndWeave(FallbackAdvice.class);
    assertEquals(
        "fallback-charge", new FallbackTarget().charge(), "caller must observe the fallback value");
  }

  @Test
  void suppressExceptions_swallowsRealExceptionToTheCaller() {
    loadAndWeave(SuppressAdvice.class);
    assertDoesNotThrow(() -> new SuppressTarget().submit());
    assertEquals(1, SuppressTarget.bodyRuns, "body must have run before the exception was swallowed");
  }

  @Test
  void denyAll_skipsBodyAndReturnsNull() {
    loadAndWeave(DenyAdvice.class);
    assertNull(new DenyTarget().adminOp(), "denied call must return null");
    assertFalse(DenyTarget.bodyRan, "method body must be skipped entirely");
  }

  @Test
  void preAuthorize_deniesNegativeAmountAndAllowsPositive() {
    loadAndWeave(PreAuthAdvice.class);
    PreAuthTarget target = new PreAuthTarget();
    assertNull(target.withdraw(-5), "negative amount must be denied");
    assertEquals(0, PreAuthTarget.bodyRuns, "denied call must not run the body");
    assertEquals("ok:50", target.withdraw(50));
    assertEquals(1, PreAuthTarget.bodyRuns, "allowed call must run the body");
  }

  @Test
  void withMdc_scopesThreadContextToMethodAndRestoresOnExit() {
    loadAndWeave(MdcAdvice.class);
    ThreadContext.put("eai.tenant", "prior-tenant");
    MdcTarget target = new MdcTarget();
    target.withContext("tenant-7");
    assertEquals("tenant-7", MdcTarget.seenTenant, "method body must observe the scoped key");
    assertEquals(
        "prior-tenant",
        ThreadContext.get("eai.tenant"),
        "prior value must be restored after the method exits");
  }

  @Test
  void spanAttribute_recordsArgAndReturnAsSpanAttributes() {
    loadAndWeave(SpanAdvice.class);
    SpanAdvice.captured.clear();
    SpanTarget target = new SpanTarget();
    target.spanTarget("order-9");
    target.spanTarget2();
    assertTrue(
        SpanAdvice.captured.contains("id:order-9"),
        "span attribute from argument must be observable in after advice: "
            + SpanAdvice.captured);
    assertTrue(
        SpanAdvice.captured.contains("status:paid"),
        "span attribute from return value must be observable in after advice: "
            + SpanAdvice.captured);
  }

  @Test
  void warnIfSlow_leavesFastCallUntouched() {
    loadAndWeave(SlowAdvice.class);
    assertEquals("ok", new SlowTarget().slowOp());
  }
}
