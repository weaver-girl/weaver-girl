package com.github.cc11001100.weavergirl.core.introduction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntroductionDelegateTest {

  interface Sample {
    void doIt();

    String greet(String name);

    void boom();

    void explodeError();

    void explodeChecked() throws IOException;
  }

  interface Missing {
    void notImplemented();
  }

  static class SampleDelegate implements Sample {
    boolean doItCalled;

    public void doIt() {
      doItCalled = true;
    }

    public String greet(String name) {
      return "Hello, " + name;
    }

    public void boom() {
      throw new IllegalStateException("boom");
    }

    public void explodeError() {
      throw new AssertionError("bang");
    }

    public void explodeChecked() throws IOException {
      throw new IOException("checked");
    }
  }

  private Object target;

  @BeforeEach
  void setUp() {
    target = new Object();
  }

  @AfterEach
  void tearDown() {
    IntroductionStore.clear();
  }

  private Method method(Class<?> iface, String name, Class<?>... params) throws Exception {
    return iface.getMethod(name, params);
  }

  @Test
  void intercept_noDelegateBound_returnsNull() throws Exception {
    Object result = IntroductionDelegate.intercept(target, method(Sample.class, "doIt"), new Object[0]);
    assertNull(result);
  }

  @Test
  void intercept_noArgs_invokesAndReturnsNull() throws Exception {
    SampleDelegate delegate = new SampleDelegate();
    IntroductionStore.bind(target, delegate);
    Object result = IntroductionDelegate.intercept(target, method(Sample.class, "doIt"), new Object[0]);
    assertNull(result);
    assertTrue(delegate.doItCalled);
  }

  @Test
  void intercept_nullArgsArray_invokesWithoutArguments() throws Exception {
    SampleDelegate delegate = new SampleDelegate();
    IntroductionStore.bind(target, delegate);
    IntroductionDelegate.intercept(target, method(Sample.class, "doIt"), null);
    assertTrue(delegate.doItCalled);
  }

  @Test
  void intercept_withArgs_invokesAndReturnsValue() throws Exception {
    SampleDelegate delegate = new SampleDelegate();
    IntroductionStore.bind(target, delegate);
    Object result =
        IntroductionDelegate.intercept(target, method(Sample.class, "greet", String.class), new Object[] {"Sam"});
    assertEquals("Hello, Sam", result);
  }

  @Test
  void intercept_noMatchingDelegateMethod_returnsNull() throws Exception {
    IntroductionStore.bind(target, new SampleDelegate());
    Object result = IntroductionDelegate.intercept(target, method(Missing.class, "notImplemented"), new Object[0]);
    assertNull(result);
  }

  @Test
  void intercept_delegateThrowsRuntimeException_rethrowsCause() throws Exception {
    IntroductionStore.bind(target, new SampleDelegate());
    assertThrows(
        IllegalStateException.class,
        () -> IntroductionDelegate.intercept(target, method(Sample.class, "boom"), new Object[0]));
  }

  @Test
  void intercept_delegateThrowsError_rethrowsCause() throws Exception {
    IntroductionStore.bind(target, new SampleDelegate());
    assertThrows(
        AssertionError.class,
        () -> IntroductionDelegate.intercept(target, method(Sample.class, "explodeError"), new Object[0]));
  }

  @Test
  void intercept_delegateThrowsCheckedException_wrapsInRuntimeException() throws Exception {
    IntroductionStore.bind(target, new SampleDelegate());
    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> IntroductionDelegate.intercept(target, method(Sample.class, "explodeChecked"), new Object[0]));
    assertTrue(ex.getCause() instanceof IOException);
  }

  @Test
  void intercept_argumentCountMismatch_wrapsInRuntimeException() throws Exception {
    IntroductionStore.bind(target, new SampleDelegate());
    // "greet" requires one argument; passing an empty array makes intercept() invoke the
    // no-arg overload path, which throws IllegalArgumentException (not InvocationTargetException)
    // and must be caught by the generic catch (Exception e) branch.
    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () ->
                IntroductionDelegate.intercept(
                    target, method(Sample.class, "greet", String.class), new Object[0]));
    assertEquals("Failed to invoke delegate method", ex.getMessage());
    assertTrue(ex.getCause() instanceof IllegalArgumentException);
  }
}
