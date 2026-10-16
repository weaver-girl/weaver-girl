package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.ExceptionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that {@link CatchAdvice} actually dispatches to {@link CatchInterceptor}s when a
 * woven method exits with an exception.
 *
 * <p>This is the test gap that let {@link CatchAdvice} rot: every existing test called {@code
 * CatchAdvice.onCatchEnter(...)} directly with a fabricated exception, which bypasses weaving and so
 * could never observe that {@code @Advice.Thrown} is null at method entry &mdash; the reason the old
 * implementation never fired in real instrumentation. Here a target class is generated, woven
 * through {@link WeaverTransformer}, and invoked for real.
 */
class CatchAdviceRealWeavingTest {

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("CATCH-WEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  private static void resetGlobalInterceptionSwitch() {
    try {
      Field enabledField = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      enabledField.setAccessible(true);
      enabledField.setBoolean(null, true);
      Field toggleCountField = GlobalInterceptionSwitch.class.getDeclaredField("toggleCount");
      toggleCountField.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicLong) toggleCountField.get(null)).set(0);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Test double implementing both {@link Interceptor} (required by the definition) and {@link CatchInterceptor}. */
  private abstract static class AbstractCatchInterceptor implements Interceptor, CatchInterceptor {}

  /** Weaves a fresh generated class whose {@code ping()} throws {@link RuntimeException}. */
  private static Object[] weaveThrowingTarget(AbstractCatchInterceptor interceptor) throws Exception {
    return weaveThrowingTarget(interceptor, String.class);
  }

  /** Weaves a fresh generated class whose {@code ping()} throws {@link RuntimeException}. */
  private static Object[] weaveThrowingTarget(AbstractCatchInterceptor interceptor, Class<?> returnType)
      throws Exception {
    String name = "wgcatch.Target" + System.nanoTime();
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name(name)
            .defineMethod("ping", returnType, Visibility.PUBLIC)
            .intercept(ExceptionMethod.throwing(RuntimeException.class, "boom"))
            .make()
            .load(CatchAdviceRealWeavingTest.class.getClassLoader())
            .getLoaded();

    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(reg);
    reg.register(
        new InterceptorDefinition(
            "catch-real-weave",
            new Pointcut(ClassMatcher.byName(name), MethodMatcher.any()),
            interceptor,
            0,
            InterceptorDefinition.AdviceMode.CATCH,
            false,
            null,
            RuntimeException.class.getName()));

    WeaverTransformer transformer = new WeaverTransformer(reg);
    transformer.install(instrumentation, true);

    Object instance = target.getDeclaredConstructor().newInstance();
    Method ping = target.getMethod("ping");
    return new Object[] {target, ping, instance};
  }

  /** {@code Method.invoke} wraps the target's exception in {@link InvocationTargetException}; unwrap it. */
  private static Throwable unwrap(Throwable t) {
    return (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
  }

  @Test
  void catchInterceptor_firesWhenWovenMethodThrowsExceptionOut() throws Exception {
    AtomicReference<Throwable> seen = new AtomicReference<>();
    Object[] ctx =
        weaveThrowingTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                seen.set(invocation.getCaughtException());
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(ClassMatcher.any(), Throwable.class)
                };
              }
            });
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    Throwable thrown =
        unwrap(assertThrows(InvocationTargetException.class, () -> ping.invoke(instance)));

    assertNotNull(seen.get(), "onCatch must have fired for the woven method's exception");
    assertSame(RuntimeException.class, seen.get().getClass());
    assertEquals("boom", thrown.getMessage());
  }

  @Test
  void catchInterceptor_suppressCatch_swallowsExceptionAndReturnsNormally() throws Exception {
    AtomicInteger onCatchCalls = new AtomicInteger();
    Object[] ctx =
        weaveThrowingTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                onCatchCalls.incrementAndGet();
                invocation.suppressCatch();
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(ClassMatcher.any(), Throwable.class)
                };
              }
            });
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    // The method body still throws, but the catch interceptor suppressed it and the
    // method returns normally (default null for a String return on the exceptional path).
    Object result = ping.invoke(instance);
    assertEquals(1, onCatchCalls.get(), "onCatch must have fired exactly once");
    assertEquals(null, result, "suppressCatch must swallow the exception and return normally");
  }

  @Test
  void catchInterceptor_setCaughtException_rethrowsReplacementInsteadOfOriginal() throws Exception {
    IllegalStateException replacement = new IllegalStateException("replaced");
    AtomicReference<Throwable> seen = new AtomicReference<>();
    Object[] ctx =
        weaveThrowingTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                seen.set(invocation.getCaughtException());
                invocation.setCaughtException(replacement);
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(ClassMatcher.any(), Throwable.class)
                };
              }
            });
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    Throwable thrown =
        unwrap(assertThrows(InvocationTargetException.class, () -> ping.invoke(instance)));

    assertSame(replacement, thrown, "the interceptor's replacement must be the exception thrown");
    assertEquals("replaced", thrown.getMessage());
  }

  @Test
  void catchInterceptor_setCatchReturnValue_returnsOverrideInsteadOfThrowing() throws Exception {
    AtomicInteger onCatchCalls = new AtomicInteger();
    Object[] ctx =
        weaveThrowingTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                onCatchCalls.incrementAndGet();
                invocation.setCatchReturnValue("fallback");
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(ClassMatcher.any(), Throwable.class)
                };
              }
            });
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    Object result = ping.invoke(instance);
    assertEquals(1, onCatchCalls.get(), "onCatch must have fired exactly once");
    assertEquals(
        "fallback", result, "setCatchReturnValue must swallow the exception and return the override");
  }

  @Test
  void catchInterceptor_suppressCatch_voidMethodReturnsNormally() throws Exception {
    AtomicInteger onCatchCalls = new AtomicInteger();
    Object[] ctx =
        weaveThrowingTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                onCatchCalls.incrementAndGet();
                invocation.suppressCatch();
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(ClassMatcher.any(), Throwable.class)
                };
              }
            },
            void.class);
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    // A void method has no return value; suppressing its exception just lets it return normally.
    Object result = ping.invoke(instance);
    assertEquals(1, onCatchCalls.get(), "onCatch must have fired exactly once");
    assertNull(result, "a void method returns null through reflection");
  }
}