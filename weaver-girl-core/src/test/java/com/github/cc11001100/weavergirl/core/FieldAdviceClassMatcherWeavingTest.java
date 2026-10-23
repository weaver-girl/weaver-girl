package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.annotation.OnFieldGet;
import com.github.cc11001100.weavergirl.annotation.OnFieldSet;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut;
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
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.FieldAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that a {@code @OnFieldGet} / {@code @OnFieldSet} (or any {@link FieldInterceptor})
 * registered against a non-name {@link ClassMatcher} — interface / superclass / annotation — actually
 * fires when a woven field is read or written.
 *
 * <p>This is the field-access twin of {@link CatchAdviceClassMatcherWeavingTest}: the class gets
 * woven (its ByteBuddy type matcher resolves {@code byInterface}/{@code bySuperClass}/{@code
 * byAnnotation} correctly), but if {@link FieldAdvice#onFieldEnter} re-evaluated the pointcut with the
 * bare <em>class name</em>, {@link ClassMatcher#matches(String)} would return {@code false} for all
 * three non-name match types and the field hook would be silently never invoked. Each test weaves a
 * generated class with a real field and asserts the field hook fires on real getfield/putfield bytecode.
 */
class FieldAdviceClassMatcherWeavingTest {

  /** Marker interface for {@code byInterface}. */
  public interface FieldedContract {}

  /** Base class for {@code bySuperClass}. */
  public static class FieldedBase {
    public String baseValue = "base";
  }

  /** Marker annotation for {@code byAnnotation}. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface TracedField {}

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("FIELD-MATCHER-WEAVE-ATTACH-FAILED " + failure);
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
    // Each test installs its own transformer with eagerRetransform=true. If one is left installed,
    // it keeps matching classes loaded by later tests and weaves them a second (third, ...) time,
    // doubling every field hook. Unweave first: this resets the transformer and retransforms the
    // classes it wove back to original bytecode.
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

  /** Test double implementing both {@link Interceptor} (required by the definition) and {@link FieldInterceptor}. */
  private abstract static class AbstractFieldInterceptor implements Interceptor, FieldInterceptor {}

  /** Generates a class with a {@code String value} field, {@code getValue()}, and {@code setValue(String)}. */
  private static Class<?> generateFieldedTarget(String namePrefix) throws Exception {
    String name = namePrefix + "Target" + System.nanoTime();
    return new ByteBuddy()
        .subclass(Object.class)
        .name(name)
        .defineField("value", String.class, Visibility.PUBLIC)
        .defineMethod("getValue", String.class, Visibility.PUBLIC)
        .intercept(FieldAccessor.ofField("value"))
        .defineMethod("setValue", void.class, Visibility.PUBLIC)
        .withParameters(String.class)
        .intercept(FieldAccessor.ofField("value"))
        .make()
        .load(FieldAdviceClassMatcherWeavingTest.class.getClassLoader())
        .getLoaded();
  }

  /** Registers the field interceptor and installs the transformer for the given matcher. */
  private Object[] weaveFieldTarget(
      AbstractFieldInterceptor interceptor,
      Class<?> target,
      ClassMatcher classMatcher,
      String fieldName)
      throws Exception {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(reg);
    reg.register(
        new InterceptorDefinition(
            "field-matcher-weave",
            new Pointcut(classMatcher, MethodMatcher.any()),
            interceptor,
            0,
            InterceptorDefinition.AdviceMode.FIELD,
            false,
            null,
            null));

    WeaverTransformer transformer = new WeaverTransformer(reg);
    transformer.install(instrumentation, true);
    installedTransformer = transformer;

    Object instance = target.getDeclaredConstructor().newInstance();
    Method getValue = target.getMethod("getValue");
    Method setValue = target.getMethod("setValue", String.class);
    return new Object[] {target, instance, getValue, setValue};
  }

  // --- byInterface ---

  /** Registers a field interceptor against {@code byInterface}, weaves, then reads/writes the field. */
  private Object[] weaveInterfaceTarget(AbstractFieldInterceptor interceptor) throws Exception {
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .implement(FieldedContract.class)
            .name("wgfieldiface.Target" + System.nanoTime())
            .defineField("value", String.class, Visibility.PUBLIC)
            .defineMethod("getValue", String.class, Visibility.PUBLIC)
            .intercept(FieldAccessor.ofField("value"))
            .defineMethod("setValue", void.class, Visibility.PUBLIC)
            .withParameters(String.class)
            .intercept(FieldAccessor.ofField("value"))
            .make()
            .load(FieldAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();
    return weaveFieldTarget(
        interceptor, target, ClassMatcher.byInterface(FieldedContract.class.getName()), "value");
  }

  /** Weaves a subclass of the base class, matched via {@code bySuperClass}. */
  private Object[] weaveSubclassTarget(AbstractFieldInterceptor interceptor) throws Exception {
    Class<?> target =
        new ByteBuddy()
            .subclass(FieldedBase.class)
            .name("wgfieldsup.Target" + System.nanoTime())
            .defineField("value", String.class, Visibility.PUBLIC)
            .defineMethod("getValue", String.class, Visibility.PUBLIC)
            .intercept(FieldAccessor.ofField("value"))
            .defineMethod("setValue", void.class, Visibility.PUBLIC)
            .withParameters(String.class)
            .intercept(FieldAccessor.ofField("value"))
            .make()
            .load(FieldAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();
    return weaveFieldTarget(
        interceptor, target, ClassMatcher.bySuperClass(FieldedBase.class.getName()), "value");
  }

  /** Weaves a generated class annotated with the marker, matched via {@code byAnnotation}. */
  private Object[] weaveAnnotatedTarget(AbstractFieldInterceptor interceptor) throws Exception {
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name("wgfieldanno.Target" + System.nanoTime())
            .annotateType(AnnotationDescription.Builder.ofType(TracedField.class).build())
            .defineField("value", String.class, Visibility.PUBLIC)
            .defineMethod("getValue", String.class, Visibility.PUBLIC)
            .intercept(FieldAccessor.ofField("value"))
            .defineMethod("setValue", void.class, Visibility.PUBLIC)
            .withParameters(String.class)
            .intercept(FieldAccessor.ofField("value"))
            .make()
            .load(FieldAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();
    return weaveFieldTarget(
        interceptor, target, ClassMatcher.byAnnotation(TracedField.class.getName()), "value");
  }

  @Test
  void byInterface_matcher_dispatchesFieldGetHook() throws Exception {
    AtomicInteger onGetCalls = new AtomicInteger();
    Object[] ctx =
        weaveInterfaceTarget(
            new AbstractFieldInterceptor() {
              @Override
              public void onFieldGet(FieldInvocation invocation, Object originalValue) {
                onGetCalls.incrementAndGet();
              }

              @Override
              public FieldPointcut[] fieldPointcuts() {
                return new FieldPointcut[] {
                  FieldPointcut.inClass(
                      ClassMatcher.byInterface(FieldedContract.class.getName()),
                      FieldMatcher.byName("value"))
                };
              }
            });
    Object instance = ctx[1];
    Method getValue = (Method) ctx[2];

    getValue.invoke(instance);

    assertEquals(
        1, onGetCalls.get(), "onFieldGet must have fired via byInterface class matcher on a real getfield");
  }

  @Test
  void byInterface_matcher_dispatchesFieldSetHook() throws Exception {
    AtomicInteger onSetCalls = new AtomicInteger();
    Object[] ctx =
        weaveInterfaceTarget(
            new AbstractFieldInterceptor() {
              @Override
              public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
                onSetCalls.incrementAndGet();
              }

              @Override
              public FieldPointcut[] fieldPointcuts() {
                return new FieldPointcut[] {
                  FieldPointcut.inClass(
                      ClassMatcher.byInterface(FieldedContract.class.getName()),
                      FieldMatcher.byName("value"))
                };
              }
            });
    Object instance = ctx[1];
    Method setValue = (Method) ctx[3];

    setValue.invoke(instance, "hello");

    assertEquals(
        1, onSetCalls.get(), "onFieldSet must have fired via byInterface class matcher on a real putfield");
  }

  @Test
  void bySuperClass_matcher_dispatchesFieldHook() throws Exception {
    AtomicInteger onGetCalls = new AtomicInteger();
    AtomicInteger onSetCalls = new AtomicInteger();
    Object[] ctx =
        weaveSubclassTarget(
            new AbstractFieldInterceptor() {
              @Override
              public void onFieldGet(FieldInvocation invocation, Object originalValue) {
                onGetCalls.incrementAndGet();
              }

              @Override
              public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
                onSetCalls.incrementAndGet();
              }

              @Override
              public FieldPointcut[] fieldPointcuts() {
                return new FieldPointcut[] {
                  FieldPointcut.inClass(
                      ClassMatcher.bySuperClass(FieldedBase.class.getName()),
                      FieldMatcher.byName("value"))
                };
              }
            });
    Object instance = ctx[1];
    Method getValue = (Method) ctx[2];
    Method setValue = (Method) ctx[3];

    setValue.invoke(instance, "world");
    getValue.invoke(instance);

    assertEquals(1, onSetCalls.get(), "onFieldSet must have fired via bySuperClass class matcher");
    assertEquals(1, onGetCalls.get(), "onFieldGet must have fired via bySuperClass class matcher");
  }

  @Test
  void byAnnotation_matcher_dispatchesFieldHook() throws Exception {
    AtomicInteger onGetCalls = new AtomicInteger();
    Object[] ctx =
        weaveAnnotatedTarget(
            new AbstractFieldInterceptor() {
              @Override
              public void onFieldGet(FieldInvocation invocation, Object originalValue) {
                onGetCalls.incrementAndGet();
              }

              @Override
              public FieldPointcut[] fieldPointcuts() {
                return new FieldPointcut[] {
                  FieldPointcut.inClass(
                      ClassMatcher.byAnnotation(TracedField.class.getName()),
                      FieldMatcher.byName("value"))
                };
              }
            });
    Object instance = ctx[1];
    Method getValue = (Method) ctx[2];

    getValue.invoke(instance);

    assertEquals(
        1, onGetCalls.get(), "onFieldGet must have fired via byAnnotation class matcher on a real getfield");
  }

  // --- Annotation-mode path ---

  /** Marker interface referenced by {@code @WeaveClass(targetInterface=...)} below. */
  public interface AnnotatedFieldContract {}

  /** Aspect declared in annotation style: select by interface, intercept the {@code value} field. */
  @WeaveClass(targetInterface = "com.github.cc11001100.weavergirl.core.FieldAdviceClassMatcherWeavingTest$AnnotatedFieldContract")
  public static class AnnotatedFieldAspect {
    static final AtomicInteger hookCalls = new AtomicInteger(0);

    @OnFieldGet("value")
    public void onValueRead(FieldInvocation invocation) {
      hookCalls.incrementAndGet();
    }

    @OnFieldSet("value")
    public void onValueWrite(FieldInvocation invocation) {
      hookCalls.incrementAndGet();
    }
  }

  /**
   * The <em>annotation</em> path end-to-end: {@code @OnFieldGet}/{@code @OnFieldSet} on an aspect
   * declared with {@code @WeaveClass(targetInterface=...)} must fire when a woven implementing class
   * reads/writes the field. Everything goes through the real loader, registry, and weave.
   */
  @Test
  void annotationMode_interfaceSelector_fieldHooksFire() throws Exception {
    AnnotatedFieldAspect.hookCalls.set(0);
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(reg);
    AnnotationPluginLoader loader = new AnnotationPluginLoader();
    java.util.Set<Class<?>> aspects = new java.util.HashSet<>();
    aspects.add(AnnotatedFieldAspect.class);
    loader.loadAnnotatedInterceptors(aspects, reg);

    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .implement(AnnotatedFieldContract.class)
            .name("wgfieldAnnIface.Target" + System.nanoTime())
            .defineField("value", String.class, Visibility.PUBLIC)
            .defineMethod("getValue", String.class, Visibility.PUBLIC)
            .intercept(FieldAccessor.ofField("value"))
            .defineMethod("setValue", void.class, Visibility.PUBLIC)
            .withParameters(String.class)
            .intercept(FieldAccessor.ofField("value"))
            .make()
            .load(FieldAdviceClassMatcherWeavingTest.class.getClassLoader())
            .getLoaded();

    WeaverTransformer transformer = new WeaverTransformer(reg);
    transformer.install(instrumentation, true);
    installedTransformer = transformer;

    Object instance = target.getDeclaredConstructor().newInstance();
    Method getValue = target.getMethod("getValue");
    Method setValue = target.getMethod("setValue", String.class);

    getValue.invoke(instance);
    setValue.invoke(instance, "x");

    assertEquals(
        2,
        AnnotatedFieldAspect.hookCalls.get(),
        "@OnFieldGet/@OnFieldSet via @WeaveClass(targetInterface=...) must actually dispatch");
  }
}
