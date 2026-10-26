package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutParser;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The static-initializer advice must stay silent when interception is off, no registry is installed
 * or the call is not sampled, and a hook that throws — or whose bookkeeping throws — must never
 * escape to the class being initialized.
 *
 * <p>JaCoCo never counts these branches through a woven {@code <clinit>}: the advice is inlined into
 * the target class, so the probes that fire belong to that class, and {@code <clinit>} runs exactly
 * once per class loader. The advice methods are ordinary {@code public static} methods, so calling
 * them directly executes the same bytecode the report measures.
 */
class StaticInitAdviceGuardTest {

  private static final Class<?> TARGET = StaticInitAdviceGuardTest.class;

  @BeforeEach
  void setUp() {
    InterceptorHolder.setInterceptionEnabled(true, "test");
    SamplingController.getInstance().setMaxRate(Integer.MAX_VALUE);
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    InterceptorHolder.setInterceptionEnabled(true, "test");
    SamplingController.getInstance().setMaxRate(100);
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @Test
  void bothHooksRunAndReturnTheSameInvocation() {
    AtomicInteger beforeCalls = new AtomicInteger();
    AtomicInteger afterCalls = new AtomicInteger();
    register(TARGET.getName(), "<clinit>", counting(beforeCalls, afterCalls));

    Object entered = StaticInitAdvice.onMethodEnter(TARGET);

    assertTrue(entered instanceof MethodInvocation);
    StaticInitAdvice.onMethodExit(entered, TARGET);
    assertEquals(1, beforeCalls.get());
    assertEquals(1, afterCalls.get());
  }

  @Test
  void aDisabledSwitchSkipsBothHooks() {
    AtomicInteger calls = new AtomicInteger();
    register(TARGET.getName(), "<clinit>", counting(calls, calls));
    InterceptorHolder.setInterceptionEnabled(false, "test");

    assertNull(StaticInitAdvice.onMethodEnter(TARGET));
    assertEquals(0, calls.get());

    // The exit advice still has an invocation to release when the switch flips off between enter and
    // exit, and must do it without running the hook.
    Object entered = enterWithSwitchOn();
    InterceptorHolder.setInterceptionEnabled(false, "test");
    StaticInitAdvice.onMethodExit(entered, TARGET);
    assertEquals(1, calls.get());
  }

  @Test
  void noRegistrySkipsBothHooks() {
    InterceptorHolder.setRegistry(null);

    assertNull(StaticInitAdvice.onMethodEnter(TARGET));

    // Exit with the switch on but no registry releases the rebuilt invocation and returns.
    StaticInitAdvice.onMethodExit("not an invocation", TARGET);
  }

  @Test
  void aDroppedSampleSkipsTheBeforeHookButTheExitHookStillRuns() {
    AtomicInteger beforeCalls = new AtomicInteger();
    AtomicInteger afterCalls = new AtomicInteger();
    register(TARGET.getName(), "<clinit>", counting(beforeCalls, afterCalls));
    SamplingController controller = SamplingController.getInstance();
    controller.setSamplingRate(Integer.MAX_VALUE);
    controller.resetCounter();

    // A dropped sample returns null, so the exit advice finds no invocation and rebuilds one.
    assertNull(StaticInitAdvice.onMethodEnter(TARGET));
    StaticInitAdvice.onMethodExit(null, TARGET);

    assertEquals(0, beforeCalls.get());
    assertEquals(1, afterCalls.get());
  }

  @Test
  void aThrowingHookIsSwallowed() {
    register(
        TARGET.getName(),
        "<clinit>",
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            throw new IllegalStateException("before failed");
          }

          @Override
          public void after(MethodInvocation invocation) {
            throw new IllegalStateException("after failed");
          }
        });

    Object entered = StaticInitAdvice.onMethodEnter(TARGET);
    assertTrue(entered instanceof MethodInvocation);
    StaticInitAdvice.onMethodExit(entered, TARGET);
  }

  @Test
  void anUnmatchedPointcutAndAnOpenCircuitAreSkipped() {
    AtomicInteger calls = new AtomicInteger();
    // cflow() is false while nothing is on the call stack, so the pointcut matches the class but not
    // the join point and the loop continues.
    register(TARGET.getName(), "cflow(execution(* com.example.Caller.call(..)))", counting(calls, calls));
    // A second definition matches, but its breaker is already open.
    register(TARGET.getName(), "<clinit>", counting(calls, calls), "open-breaker");
    trip(openBreakerName());

    Object entered = StaticInitAdvice.onMethodEnter(TARGET);
    StaticInitAdvice.onMethodExit(entered, TARGET);

    assertEquals(0, calls.get());
  }

  @Test
  void aRegistryThatIsNotTheDefaultOneIsStillDispatched() {
    AtomicInteger calls = new AtomicInteger();
    InterceptorHolder.setRegistry(fixedRegistry(TARGET.getName(), counting(calls, calls)));

    Object entered = StaticInitAdvice.onMethodEnter(TARGET);
    StaticInitAdvice.onMethodExit(entered, TARGET);

    assertEquals(2, calls.get());
  }

  @Test
  void aNullTargetIsSwallowedRatherThanAbortingInitialization() {
    // Every step up to reading the class name tolerates a null target. getName() is what throws, and
    // the outermost catch must turn that into a null return instead of an ExceptionInInitializerError.
    // The earlier guards have to be open, otherwise execution returns before the class name is read.
    InterceptorHolder.setRegistry(new DefaultInterceptorRegistry());
    assertNull(StaticInitAdvice.onMethodEnter(null));
  }

  @Test
  void releasingAnInvocationThatRejectsClearingIsSwallowed() {
    InterceptorHolder.setInterceptionEnabled(false, "test");
    MethodInvocation broken =
        new MethodInvocation(TARGET, "<clinit>", null, null, new Object[0]) {
          @Override
          public void clear() {
            throw new IllegalStateException("pool rejected it");
          }
        };

    // The switch is off, so exit only tries to release the invocation it was handed. A release that
    // throws must be swallowed by the inner catch rather than escaping to the class being initialized.
    StaticInitAdvice.onMethodExit(broken, TARGET);
  }

  @Test
  void aFailureWhileReleasingTheInvocationIsSwallowed() {
    // The outer catch runs when the exit advice itself fails. A registry that throws on lookup does
    // that, and the release that follows is handed an invocation whose own release throws.
    InterceptorHolder.setRegistry(throwingRegistry());
    MethodInvocation broken = new MethodInvocation(TARGET, "<clinit>", null, null, new Object[0]) {
      @Override
      public void clear() {
        throw new IllegalStateException("pool rejected it");
      }
    };

    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));
    try {
      StaticInitAdvice.onMethodExit(broken, TARGET);
    } finally {
      System.setErr(original);
    }

    assertTrue(captured.toString().contains("Failed to release MethodInvocation to pool"));
  }

  /** Enters with interception on and returns the invocation, so a test can flip the switch after. */
  private Object enterWithSwitchOn() {
    InterceptorHolder.setInterceptionEnabled(true, "test");
    Object entered = StaticInitAdvice.onMethodEnter(TARGET);
    assertTrue(entered instanceof MethodInvocation);
    return entered;
  }

  private static void register(String className, String expression, Interceptor interceptor) {
    register(className, expression, interceptor, "clinit-" + className + "-" + expression);
  }

  private static void register(
      String className, String expression, Interceptor interceptor, String name) {
    if (!(InterceptorHolder.getRegistry() instanceof DefaultInterceptorRegistry)) {
      InterceptorHolder.setRegistry(new DefaultInterceptorRegistry());
    }
    Pointcut pointcut =
        "<clinit>".equals(expression)
            ? new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName("<clinit>"))
            : PointcutParser.getInstance()
                .parse(expression)
                .toPointcutWithClassScope(ClassMatcher.byName(className));
    ((DefaultInterceptorRegistry) InterceptorHolder.getRegistry())
        .register(
            new InterceptorDefinition(
                name, pointcut, interceptor, 0, InterceptorDefinition.AdviceMode.STATIC_INIT));
  }

  /** Records enough consecutive failures to open the breaker for the named interceptor. */
  private static void trip(String name) {
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordOutcome(name, false, 0L);
    }
  }

  private static String openBreakerName() {
    return "open-breaker";
  }

  private static Interceptor counting(final AtomicInteger beforeCalls, final AtomicInteger afterCalls) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation invocation) {
        beforeCalls.incrementAndGet();
      }

      @Override
      public void after(MethodInvocation invocation) {
        afterCalls.incrementAndGet();
      }
    };
  }

  /** A registry that is not a {@code DefaultInterceptorRegistry}, so the snapshot takes its slow path. */
  private static InterceptorRegistry fixedRegistry(String className, Interceptor interceptor) {
    InterceptorDefinition definition =
        new InterceptorDefinition(
            "foreign",
            new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName("<clinit>")),
            interceptor,
            0,
            InterceptorDefinition.AdviceMode.STATIC_INIT);
    return new InterceptorRegistry() {
      @Override
      public void register(InterceptorDefinition ignored) {}

      @Override
      public boolean unregister(String name) {
        return false;
      }

      @Override
      public List<InterceptorDefinition> getInterceptorsForClass(String wanted) {
        return className.equals(wanted) ? Collections.singletonList(definition) : Collections.emptyList();
      }

      @Override
      public List<InterceptorDefinition> getAllDefinitions() {
        return Collections.singletonList(definition);
      }
    };
  }

  /** A registry whose lookup throws, driving the exit advice into its outermost catch. */
  private static InterceptorRegistry throwingRegistry() {
    return new InterceptorRegistry() {
      @Override
      public void register(InterceptorDefinition ignored) {}

      @Override
      public boolean unregister(String name) {
        return false;
      }

      @Override
      public List<InterceptorDefinition> getInterceptorsForClass(String wanted) {
        throw new IllegalStateException("registry unavailable");
      }

      @Override
      public List<InterceptorDefinition> getAllDefinitions() {
        return Collections.emptyList();
      }
    };
  }

}
