package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.cc11001100.weavergirl.annotation.OnStaticInit;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that {@code @OnStaticInit} is a <em>real</em> capability: the {@code <clinit>}
 * of a woven target class is intercepted and the annotated hook actually fires.
 *
 * <p>This guards against the "registered but never woven" class of defect. The old {@code
 * staticInit_shouldBeRegistered} test only asserted a {@code <clinit>} definition existed in the
 * registry; it never proved the class initializer was instrumented. Here the target is loaded for
 * the first time <em>after</em> the transformer is installed (the real premain path), so the woven
 * {@code <clinit>} executes at first class initialization and must drive the {@code @OnStaticInit}
 * hook.
 *
 * <p>The target lives in its own source file ({@code com.example.staticinit.TargetWithClinit}) and
 * is never referenced by this class except through {@link Class#forName(String, boolean, ClassLoader)}
 * with {@code initialize=true}, so first active use happens after install.
 */
class StaticInitRealWeavingTest {

  @WeaveClass(target = "com.example.staticinit.TargetWithClinit")
  public static class StaticInitAspect {
    static final AtomicInteger hookCalls = new AtomicInteger(0);

    @OnStaticInit
    public void onClassLoad(MethodInvocation inv) {
      hookCalls.incrementAndGet();
    }
  }

  private static Instrumentation instrumentation;
  private static DefaultInterceptorRegistry registry;

  @BeforeAll
  static void attachAndInstall() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("STATICINIT-WEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);

    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    Set<Class<?>> aspects = new HashSet<>();
    aspects.add(StaticInitAspect.class);
    loader.loadAnnotatedInterceptors(aspects, registry);

    WeaverTransformer transformer = new WeaverTransformer(registry);
    // eagerRetransform=true: nothing for the target to match yet (it is not loaded), but the flag
    // mirrors the CacheRealWeavingTest harness. The transform-on-first-load path is what the test
    // exercises — the target is defined only after this install returns.
    transformer.install(instrumentation, true);
  }

  @BeforeEach
  void setUp() {
    InterceptorHolder.setRegistry(registry);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
    StaticInitAspect.hookCalls.set(0);
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

  private static Class<?> loadAndInitializeTarget() throws Exception {
    return Class.forName(
        "com.example.staticinit.TargetWithClinit",
        true,
        StaticInitRealWeavingTest.class.getClassLoader());
  }

  /**
   * The two assertions this guards — "first load fires the hook" and "subsequent accesses don't
   * re-fire it" — describe one phenomenon and are deliberately kept in a single test method.
   * A class's {@code <clinit>} runs exactly once per classloader, so two test methods that each
   * initialize the same target would race on JUnit's method order: whichever ran second would see a
   * class that was already initialized and fail its "first load" assertion. Folding both checks into
   * one method makes the exactly-once semantics explicit and order-independent.
   */
  @Test
  void classInitializerHookFiresExactlyOnceOnFirstLoad() throws Exception {
    Class<?> target = loadAndInitializeTarget();
    // The woven <clinit> ran (side effect of the target's own static block), proving the class was
    // actually loaded and initialized rather than short-circuited.
    assertEquals(1, (int) target.getField("INIT_RUNS").get(null));
    assertEquals(
        1,
        StaticInitAspect.hookCalls.get(),
        "first class initialization must fire the @OnStaticInit hook exactly once");

    // Re-accessing the initialized class must NOT re-run <clinit> or the hook.
    int accessed =
        (int) target.getField("INIT_RUNS").get(null)
            + target.getField("INIT_RUNS").getInt(null);
    assertEquals(2, accessed);
    assertEquals(
        1,
        StaticInitAspect.hookCalls.get(),
        "subsequent access must not re-fire the hook");
  }
}
