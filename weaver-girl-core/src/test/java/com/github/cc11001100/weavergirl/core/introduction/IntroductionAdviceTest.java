package com.github.cc11001100.weavergirl.core.introduction;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntroductionAdviceTest {

  interface Sample {
    void doIt();

    String greet(String name);

    void boom();

    void explodeChecked() throws IOException;
  }

  interface Missing {
    void notImplemented();
  }

  static class SampleDelegate implements Sample {
    boolean doItCalled;
    String lastGreetArg;

    public void doIt() {
      doItCalled = true;
    }

    public String greet(String name) {
      lastGreetArg = name;
      return "Hello, " + name;
    }

    public void boom() {
      throw new IllegalStateException("boom");
    }

    public void explodeChecked() throws IOException {
      throw new IOException("checked");
    }
  }

  static class ThrowingRegistry extends IntroductionRegistry {
    @Override
    public List<IntroductionDefinition> getDefinitionsForClass(String className) {
      throw new RuntimeException("registry lookup failed");
    }
  }

  static class TargetClass {}

  private Object target;

  @BeforeEach
  void setUp() {
    target = new Object();
  }

  @AfterEach
  void tearDown() {
    IntroductionAdvice.setIntroductionRegistry(null);
    IntroductionStore.clear();
  }

  private Method method(Class<?> iface, String name, Class<?>... params) throws Exception {
    return iface.getMethod(name, params);
  }

  @Test
  void onMethodEnter_noRegistry_returnsNull() throws Exception {
    IntroductionAdvice.setIntroductionRegistry(null);
    Object result =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Sample.class, "doIt"), target, new Object[0]);
    assertNull(result);
  }

  @Test
  void onMethodEnter_noMatchingDefinition_returnsNull() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    registry.register(
        new IntroductionDefinition(
            "other", ClassMatcher.byName("com.example.Other"), Sample.class, new SampleDelegate()));
    IntroductionAdvice.setIntroductionRegistry(registry);

    Object result =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Sample.class, "doIt"), target, new Object[0]);
    assertNull(result);
  }

  @Test
  void onMethodEnter_matchingDefinitionButNoDelegateBound_returnsNull() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, new SampleDelegate()));
    IntroductionAdvice.setIntroductionRegistry(registry);

    Object result =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Sample.class, "doIt"), target, new Object[0]);
    assertNull(result);
  }

  @Test
  void onMethodEnter_delegateBoundButNoMatchingMethod_returnsNull() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, new SampleDelegate()));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, new SampleDelegate());

    Object result =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Missing.class, "notImplemented"), target, new Object[0]);
    assertNull(result);
  }

  @Test
  void onMethodEnter_registryThrows_isCaughtAndReturnsNull() throws Exception {
    IntroductionAdvice.setIntroductionRegistry(new ThrowingRegistry());
    Object result =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Sample.class, "doIt"), target, new Object[0]);
    assertNull(result);
  }

  @Test
  void onMethodEnter_matchFound_returnsNonNullEnterValue() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, new SampleDelegate()));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, new SampleDelegate());

    Object result =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Sample.class, "doIt"), target, new Object[0]);
    assertNotNull(result);
  }

  @Test
  void roundTrip_noArgMethod_invokesDelegateAndUpdatesReturnValue() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    SampleDelegate delegate = new SampleDelegate();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, delegate));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, delegate);

    Object enterValue =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Sample.class, "doIt"), target, new Object[0]);
    assertNotNull(enterValue);
    assertDoesNotThrow(() -> IntroductionAdvice.onMethodExit(enterValue, null, null));
    assertTrue(delegate.doItCalled);
  }

  @Test
  void roundTrip_withArgsMethod_forwardsArgumentsToDelegate() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    SampleDelegate delegate = new SampleDelegate();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, delegate));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, delegate);

    Object enterValue =
        IntroductionAdvice.onMethodEnter(
            TargetClass.class, method(Sample.class, "greet", String.class), target, new Object[] {"Sam"});
    assertNotNull(enterValue);
    assertDoesNotThrow(() -> IntroductionAdvice.onMethodExit(enterValue, null, null));
    assertEquals("Sam", delegate.lastGreetArg);
  }

  @Test
  void onMethodExit_notADelegateInvocation_isNoOp() {
    assertDoesNotThrow(() -> IntroductionAdvice.onMethodExit(null, "unchanged", null));
    assertDoesNotThrow(() -> IntroductionAdvice.onMethodExit("not-a-delegate-invocation", null, null));
  }

  @Test
  void onMethodExit_existingThrowable_skipsReturnValueAssignment() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    SampleDelegate delegate = new SampleDelegate();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, delegate));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, delegate);

    Object enterValue =
        IntroductionAdvice.onMethodEnter(
            TargetClass.class, method(Sample.class, "greet", String.class), target, new Object[] {"Sam"});
    assertDoesNotThrow(
        () -> IntroductionAdvice.onMethodExit(enterValue, null, new RuntimeException("already thrown")));
  }

  @Test
  void onMethodExit_delegateThrowsRuntimeException_doesNotPropagate() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    SampleDelegate delegate = new SampleDelegate();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, delegate));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, delegate);

    Object enterValue =
        IntroductionAdvice.onMethodEnter(TargetClass.class, method(Sample.class, "boom"), target, new Object[0]);
    assertDoesNotThrow(() -> IntroductionAdvice.onMethodExit(enterValue, null, null));
  }

  @Test
  void onMethodExit_delegateThrowsCheckedException_doesNotPropagate() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    SampleDelegate delegate = new SampleDelegate();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, delegate));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, delegate);

    Object enterValue =
        IntroductionAdvice.onMethodEnter(
            TargetClass.class, method(Sample.class, "explodeChecked"), target, new Object[0]);
    assertDoesNotThrow(() -> IntroductionAdvice.onMethodExit(enterValue, null, null));
  }

  @Test
  void onMethodExit_argumentCountMismatch_isCaughtByGenericHandler() throws Exception {
    IntroductionRegistry registry = new IntroductionRegistry();
    SampleDelegate delegate = new SampleDelegate();
    registry.register(
        new IntroductionDefinition(
            "intro", ClassMatcher.byName(TargetClass.class.getName()), Sample.class, delegate));
    IntroductionAdvice.setIntroductionRegistry(registry);
    IntroductionStore.bind(target, delegate);

    // "greet" requires one argument; capturing zero arguments at enter time makes the exit-time
    // invoke() throw IllegalArgumentException (not InvocationTargetException), which must be
    // caught by the generic catch (Throwable e) branch in onMethodExit.
    Object enterValue =
        IntroductionAdvice.onMethodEnter(
            TargetClass.class, method(Sample.class, "greet", String.class), target, new Object[0]);
    assertNotNull(enterValue);
    assertDoesNotThrow(() -> IntroductionAdvice.onMethodExit(enterValue, null, null));
  }
}
