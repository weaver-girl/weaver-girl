package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.cc11001100.weavergirl.annotation.OnCatch;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.ExceptionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that a {@code @OnCatch} (or any {@link CatchInterceptor}) registered against a
 * non-name {@link ClassMatcher} — interface / superclass / annotation — actually fires when a woven
 * method throws.
 *
 * <p>This guards the "woven but never dispatched" defect: the {@code WeaverTransformer} weaves the
 * target (its ByteBuddy type matcher resolves {@code byInterface}/{@code bySuperClass}/{@code
 * byAnnotation} correctly), but {@link CatchAdvice#dispatch} re-evaluated the pointcut with the
 * bare <em>class name</em>, and {@link ClassMatcher#matches(String)} returns {@code false} for all
 * three non-name match types, so the interceptor was silently never invoked. Each test below weaves a
 * generated target and asserts the catch hook actually fires.
 */
class CatchAdviceClassMatcherWeavingTest {

  /** Marker interface for {@code byInterface}. */
  public interface ThrowingContract {
    String ping();
  }

  /** Base class for {@code bySuperClass}. */
  public static class ThrowingBase {
    public String ping() {
      throw new RuntimeException("boom");
    }
  }

  /** Marker annotation for {@code byAnnotation}. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface Traced {}

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("CATCH-MATCHER-WEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  private DefaultInterceptorRegistry registry;

  /** The transformer installed by the current test, unwove in tearDown to avoid cross-test pollution. */
  private WeaverTransformer installedTransformer;

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
    // Each test installs its own transformer with eagerRetransform=true. If one is left
    // installed, it keeps matching classes loaded by later tests and weaves them a second
    // (third, ...) time, doubling every catch hook.
    if (installedTransformer != null) {
      installedTransformer.unweave();
    }
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

  /** Weaves a generated class that implements the marker interface, matched via {@code byInterface}. */
  private Object[] weaveInterfaceTarget(AbstractCatchInterceptor interceptor) throws Exception {
    String name = "wgcatchiface.Target" + System.nanoTime();
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .implement(ThrowingContract.class)
            .name(name)
            .defineMethod("ping", String.class, Visibility.PUBLIC)
            .intercept(ExceptionMethod.throwing(RuntimeException.class, "boom"))
            .make()
            .load(CatchAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();
    return weaveTarget(interceptor, target, ClassMatcher.byInterface(ThrowingContract.class.getName()));
  }

  /** Weaves a generated subclass of the base class, matched via {@code bySuperClass}. */
  private Object[] weaveSubclassTarget(AbstractCatchInterceptor interceptor) throws Exception {
    String name = "wgcatchsup.Target" + System.nanoTime();
    Class<?> target =
        new ByteBuddy()
            .subclass(ThrowingBase.class)
            .name(name)
            .defineMethod("ping", String.class, Visibility.PUBLIC)
            .intercept(ExceptionMethod.throwing(RuntimeException.class, "boom"))
            .make()
            .load(CatchAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();
    return weaveTarget(interceptor, target, ClassMatcher.bySuperClass(ThrowingBase.class.getName()));
  }

  /** Weaves a generated class annotated with the marker, matched via {@code byAnnotation}. */
  private Object[] weaveAnnotatedTarget(AbstractCatchInterceptor interceptor) throws Exception {
    String name = "wgcatchanno.Target" + System.nanoTime();
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name(name)
            .annotateType(AnnotationDescription.Builder.ofType(Traced.class).build())
            .defineMethod("ping", String.class, Visibility.PUBLIC)
            .intercept(ExceptionMethod.throwing(RuntimeException.class, "boom"))
            .make()
            .load(CatchAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();
    return weaveTarget(interceptor, target, ClassMatcher.byAnnotation(Traced.class.getName()));
  }

  /** Registers the catch interceptor and installs the transformer for the given matcher. */
  private Object[] weaveTarget(
      AbstractCatchInterceptor interceptor, Class<?> target, ClassMatcher classMatcher)
      throws Exception {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(reg);
    reg.register(
        new InterceptorDefinition(
            "catch-matcher-weave",
            new Pointcut(classMatcher, MethodMatcher.any()),
            interceptor,
            0,
            InterceptorDefinition.AdviceMode.CATCH,
            false,
            null,
            RuntimeException.class.getName()));

    WeaverTransformer transformer = new WeaverTransformer(reg);
    transformer.install(instrumentation, true);
    installedTransformer = transformer;

    Object instance = target.getDeclaredConstructor().newInstance();
    Method ping = target.getMethod("ping");
    return new Object[] {target, ping, instance};
  }

  /** {@code Method.invoke} wraps the target's exception in {@link InvocationTargetException}; unwrap it. */
  private static Throwable unwrap(Throwable t) {
    return (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
  }

  @Test
  void byInterface_matcher_dispatchesCatchHook() throws Exception {
    AtomicInteger onCatchCalls = new AtomicInteger();
    AtomicInteger hookSeen = new AtomicInteger();
    Object[] ctx =
        weaveInterfaceTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                onCatchCalls.incrementAndGet();
                hookSeen.set(invocation.getCaughtException().hashCode());
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(
                      ClassMatcher.byInterface(ThrowingContract.class.getName()),
                      RuntimeException.class)
                };
              }
            });
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    // The method still throws: the hook must run but not change the disposition.
    Throwable thrown = unwrap(org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException.class, () -> ping.invoke(instance)));
    assertEquals(1, onCatchCalls.get(), "onCatch must have fired via byInterface class matcher");
    assertNotNull(thrown, "an unwoven/undispatched method would not even reach the assertion");
  }

  @Test
  void bySuperClass_matcher_dispatchesCatchHook() throws Exception {
    AtomicInteger onCatchCalls = new AtomicInteger();
    Object[] ctx =
        weaveSubclassTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                onCatchCalls.incrementAndGet();
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(
                      ClassMatcher.bySuperClass(ThrowingBase.class.getName()), RuntimeException.class)
                };
              }
            });
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException.class, () -> ping.invoke(instance));
    assertEquals(1, onCatchCalls.get(), "onCatch must have fired via bySuperClass class matcher");
  }

  @Test
  void byAnnotation_matcher_dispatchesCatchHook() throws Exception {
    AtomicInteger onCatchCalls = new AtomicInteger();
    Object[] ctx =
        weaveAnnotatedTarget(
            new AbstractCatchInterceptor() {
              @Override
              public void onCatch(CatchInvocation invocation) {
                onCatchCalls.incrementAndGet();
              }

              @Override
              public CatchPointcut[] catchPointcuts() {
                return new CatchPointcut[] {
                  CatchPointcut.inClass(ClassMatcher.byAnnotation(Traced.class.getName()), RuntimeException.class)
                };
              }
            });
    Method ping = (Method) ctx[1];
    Object instance = ctx[2];

    org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException.class, () -> ping.invoke(instance));
    assertEquals(1, onCatchCalls.get(), "onCatch must have fired via byAnnotation class matcher");
  }

  // --- Annotation-mode path (the way real users declare interception) ---

  /** Marker interface referenced by {@code @WeaveClass(targetInterface=...)} below. */
  public interface AnnotatedContract {
    String ping();
  }

  /** Aspect declared in annotation style: select by interface, catch by exception type. */
  @WeaveClass(targetInterface = "com.github.cc11001100.weavergirl.core.CatchAdviceClassMatcherWeavingTest$AnnotatedContract")
  public static class AnnotatedCatchAspect {
    static final AtomicInteger hookCalls = new AtomicInteger(0);

    @OnCatch("java.lang.RuntimeException")
    public void onRuntimeException(CatchInvocation invocation) {
      hookCalls.incrementAndGet();
    }
  }

  /**
   * The <em>annotation</em> path end-to-end: an {@code @OnCatch} method on an aspect declared with
   * {@code @WeaveClass(targetInterface=...)} must fire when a woven implementing class throws.
   *
   * <p>This is the strongest guard for the "woven but never dispatched" defect: everything goes
   * through the real loader ({@code AnnotationPluginLoader} turns the interface selector into a
   * {@link ClassMatcher#byInterface}), the real registry, and the real weave — no hand-rolled
   * programmatic registration, so nothing can silently skip the annotation mode.
   */
  @Test
  void annotationMode_interfaceSelector_onCatchFires() throws Exception {
    AnnotatedCatchAspect.hookCalls.set(0);
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(reg);
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    java.util.Set<Class<?>> aspects = new java.util.HashSet<>();
    aspects.add(AnnotatedCatchAspect.class);
    loader.loadAnnotatedInterceptors(aspects, reg);

    // A generated class implementing AnnotatedContract, woven after install (like the other
    // tests in this class, the target is loaded before install and eagerRetransform=true picks
    // it up via the interface matcher).
    String name = "wgcatchAnnIface.Target" + System.nanoTime();
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .implement(AnnotatedContract.class)
            .name(name)
            .defineMethod("ping", String.class, Visibility.PUBLIC)
            .intercept(ExceptionMethod.throwing(RuntimeException.class, "boom"))
            .make()
            .load(CatchAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();

    WeaverTransformer transformer = new WeaverTransformer(reg);
    transformer.install(instrumentation, true);

    Object instance = target.getDeclaredConstructor().newInstance();
    Method ping = target.getMethod("ping");
    org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException.class, () -> ping.invoke(instance));

    assertEquals(
        1,
        AnnotatedCatchAspect.hookCalls.get(),
        "@OnCatch via @WeaveClass(targetInterface=...) must actually dispatch");
  }
}