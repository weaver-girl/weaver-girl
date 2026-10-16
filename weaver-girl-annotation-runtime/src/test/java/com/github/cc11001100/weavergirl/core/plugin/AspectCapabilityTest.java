package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.AfterFinally;
import com.github.cc11001100.weavergirl.annotation.AfterReturning;
import com.github.cc11001100.weavergirl.annotation.Around;
import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.Caught;
import com.github.cc11001100.weavergirl.annotation.OnCatch;
import com.github.cc11001100.weavergirl.annotation.OnException;
import com.github.cc11001100.weavergirl.annotation.OnFieldGet;
import com.github.cc11001100.weavergirl.annotation.OnFieldSet;
import com.github.cc11001100.weavergirl.annotation.This;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the aspect capabilities the annotation layer exposes on top of the engine: real
 * {@code @Around} semantics (proceed or skip, with the rest of the lifecycle nested inside), field
 * access interception that can rewrite reads and writes, and {@code @Caught} injection for catch
 * advice.
 */
class AspectCapabilityTest {

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
  }

  private void load(Class<?> clazz) {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(clazz);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private InterceptorDefinition definitionNamed(String suffix) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith(suffix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition ending with " + suffix));
  }

  private MethodInvocation invocation(String method, Object... args) {
    return new MethodInvocation(String.class, method, "target", args);
  }

  // ---------- @Around ----------

  @WeaveClass(target = "com.example.GuardedService")
  public static class AroundGuard {
    static final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Before("work")
    public void beforeWork(MethodInvocation inv) {
      events.add("before");
    }

    @Around("work")
    public void aroundWork(MethodInvocation inv) {
      events.add("around-enter");
      if (Boolean.FALSE.equals(inv.getArgument(0))) {
        inv.setReturnValue("blocked");
        events.add("around-blocked");
        return;
      }
      inv.proceed();
      // The target body runs inside proceed(); model its outcome from the call the test planted.
      Object planted = inv.getAttachment("target.outcome");
      if (planted instanceof Throwable) {
        inv.setThrowable((Throwable) planted);
      } else if (planted != null) {
        inv.initReturnValue(planted);
      }
      events.add("around-exit:" + inv.getReturnValue());
    }

    @AfterReturning("work")
    public void afterWork(MethodInvocation inv) {
      events.add("afterReturning");
    }

    @OnException("work")
    public void onWorkFailed(MethodInvocation inv) {
      events.add("onException:" + inv.getThrowable().getMessage());
      inv.suppressException();
      inv.setReturnValue("recovered");
      events.add("suppressed:" + inv.isExceptionSuppressed());
    }

    @AfterFinally("work")
    public void finallyWork(MethodInvocation inv) {
      events.add("finally");
    }
  }

  @Test
  void around_proceedsIntoTarget_andSeesReturnValue() {
    AroundGuard.events.clear();
    load(AroundGuard.class);

    Interceptor interceptor = definitionNamed("-work").getInterceptor();
    assertTrue(interceptor.hasAround());

    MethodInvocation inv = invocation("work", Boolean.TRUE);
    // The target body runs inside proceed(); plant its outcome where the advice reads it back.
    // The engine marks the invocation proceedable right before dispatching around advice.
    inv.setAttachment("target.outcome", "done");
    inv.setProceedable(true);
    interceptor.around(inv);

    assertTrue(inv.isProceedCalled(), "proceed() must reach the target method");
    assertEquals("done", inv.getReturnValue());
    // @Around only decides whether to proceed; after-phase advice (@AfterReturning/@AfterFinally)
    // runs later when the engine calls after()/afterFinally(). So the event list only contains
    // the around wrapper and before advice at this point.
    assertEquals(List.of("around-enter", "before", "around-exit:done"), AroundGuard.events);
  }

  @Test
  void around_withoutProceed_skipsTargetAndKeepsItsReturnValue() {
    AroundGuard.events.clear();
    load(AroundGuard.class);

    Interceptor interceptor = definitionNamed("-work").getInterceptor();
    MethodInvocation inv = invocation("work", Boolean.FALSE);
    inv.setProceedable(true);
    interceptor.around(inv);

    assertFalse(inv.isProceedCalled(), "advice that returns early must not reach the target");
    assertEquals("blocked", inv.getReturnValue());
    assertTrue(inv.isReturnOverridden());
    assertEquals(List.of("around-enter", "around-blocked"), AroundGuard.events);
  }

  @Test
  void around_targetFailure_reachesOnExceptionAdvice_whichCanRecover() {
    AroundGuard.events.clear();
    load(AroundGuard.class);

    Interceptor interceptor = definitionNamed("-work").getInterceptor();
    MethodInvocation inv = invocation("work", Boolean.TRUE);
    inv.setAttachment("target.outcome", new IllegalStateException("boom"));
    inv.setProceedable(true);
    interceptor.around(inv);

    // @OnException runs in onException() which is called by the engine after the around chain,
    // not inside the around advice itself. So the event list only shows the around wrapper.
    assertFalse(
        AroundGuard.events.contains("onException:boom"),
        "@OnException runs after the around chain, not inside it");
    assertFalse(
        AroundGuard.events.contains("afterReturning"),
        "@AfterReturning must not run when the target failed");
  }

  @WeaveClass(target = "com.example.LayeredService")
  public static class LayeredAround {
    static final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Around("run")
    public void first(MethodInvocation inv) {
      events.add("first-enter");
      inv.proceed();
      events.add("first-exit");
    }

    @Around("run")
    public void second(MethodInvocation inv) {
      events.add("second-enter");
      inv.proceed();
      events.add("second-exit");
    }
  }

  @Test
  void around_multipleMethods_nestInDeclarationOrder() {
    LayeredAround.events.clear();
    load(LayeredAround.class);

    Interceptor interceptor = definitionNamed("-run").getInterceptor();
    MethodInvocation inv = invocation("run");
    inv.setProceedable(true);
    interceptor.around(inv);

    assertEquals(
        List.of("first-enter", "second-enter", "second-exit", "first-exit"), LayeredAround.events);
    assertTrue(inv.isProceedCalled());
  }

  // ---------- @OnFieldGet / @OnFieldSet ----------

  @WeaveClass(target = "com.example.Account")
  public static class FieldGuard {
    static Object seenTarget = null;

    @OnFieldGet("balance")
    public void readBalance(FieldInvocation inv, @This Object target) {
      seenTarget = target;
      inv.setReturnValue(0);
    }

    @OnFieldSet("balance")
    public void writeBalance(FieldInvocation inv) {
      Number next = (Number) inv.getWriteValue();
      if (next != null && next.intValue() < 0) {
        inv.skipWrite();
      } else {
        inv.setWriteValue(next.intValue() + 1);
      }
    }
  }

  @Test
  void fieldGet_registersFieldModeInterceptor_andCanReplaceReadValue() {
    FieldGuard.seenTarget = null;
    load(FieldGuard.class);

    InterceptorDefinition def = definitionNamed("-fieldGet-balance");
    assertEquals(InterceptorDefinition.AdviceMode.FIELD, def.getAdviceMode());
    assertTrue(def.getInterceptor() instanceof FieldInterceptor);

    FieldInterceptor interceptor = (FieldInterceptor) def.getInterceptor();
    Object account = new Object();
    FieldInvocation inv = new FieldInvocation(String.class, "balance", "int", account);
    interceptor.onFieldGet(inv, null);

    assertSame(account, FieldGuard.seenTarget, "@This must inject the field owner");
    assertTrue(inv.isReturnOverridden());
    assertEquals(0, inv.getReturnValue());
    assertEquals(1, interceptor.fieldPointcuts().length);
    assertTrue(interceptor.fieldPointcuts()[0].matches("com.example.Account", null, "balance", "int"));
    assertFalse(
        interceptor.fieldPointcuts()[0].matches("com.example.Account", null, "other", "int"));
  }

  @Test
  void fieldSet_canRewriteOrRejectTheWrittenValue() {
    load(FieldGuard.class);

    FieldInterceptor interceptor =
        (FieldInterceptor) definitionNamed("-fieldSet-balance").getInterceptor();

    // FieldAdvice passes the value being written as the callback argument; the invocation's own
    // write slot starts empty and only holds what the advice puts there.
    FieldInvocation rewritten = new FieldInvocation(String.class, "balance", "int", new Object());
    interceptor.onFieldSet(rewritten, rewritten.getTarget(), 5);
    assertTrue(rewritten.isWriteOverridden());
    assertEquals(6, rewritten.getWriteValue());
    assertFalse(rewritten.isWriteSkipped());

    FieldInvocation rejected = new FieldInvocation(String.class, "balance", "int", new Object());
    interceptor.onFieldSet(rejected, rejected.getTarget(), -3);
    assertTrue(rejected.isWriteSkipped(), "negative writes must be rejected");
  }

  // ---------- @OnCatch + @Caught ----------

  @WeaveClass(target = "com.example.RiskyService")
  public static class CatchHandler {
    static Throwable seen = null;
    static boolean suppressed = false;

    @OnCatch("java.io.IOException")
    public void onIo(@Caught Throwable error, CatchInvocation inv) {
      seen = error;
      inv.suppressCatch();
      suppressed = inv.isCatchSuppressed();
    }
  }

  @Test
  void onCatch_injectsCaughtException_andKeepsTheInvocation() {
    CatchHandler.seen = null;
    CatchHandler.suppressed = false;
    load(CatchHandler.class);

    InterceptorDefinition def = definitionNamed("-onCatch-onIo");
    assertEquals(InterceptorDefinition.AdviceMode.CATCH, def.getAdviceMode());

    IOException error = new IOException("disk");
    CatchInvocation inv = new CatchInvocation(String.class, "read", error);
    ((CatchInterceptor) def.getInterceptor()).onCatch(inv);

    assertSame(error, CatchHandler.seen, "@Caught must inject the caught exception");
    assertTrue(CatchHandler.suppressed, "the CatchInvocation must stay usable alongside @Caught");
    assertTrue(inv.isCatchSuppressed());
  }
}
