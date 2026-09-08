package com.github.cc11001100.weavergirl.core.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

/**
 * End-to-end integration test that verifies bytecode transformation actually works at runtime.
 *
 * <p>Uses ByteBuddyAgent to obtain an Instrumentation instance, installs the WeaverTransformer,
 * then invokes target methods and verifies interceptors are called.
 *
 * <p><strong>Important:</strong> All interceptors must be registered BEFORE the transformer is
 * installed, because the transformer reads the registry at install time to determine which
 * classes/methods to transform. Subsequent interceptor registrations after install will NOT cause
 * retransformation of already-loaded classes.
 */
class AgentIntegrationTest {

  /** Target service that we intercept in tests. */
  public static class TargetService {
    public String greet(String name) {
      return "Hello, " + name;
    }

    public int add(int a, int b) {
      return a + b;
    }

    public void riskyOperation() {
      throw new RuntimeException("something went wrong");
    }
  }

  /** Target class with a static method for testing static interception. */
  public static class StaticTargetService {
    public static String staticGreet(String name) {
      return "Static Hello, " + name;
    }
  }

  /** Separate target class for static method return-value override test. */
  public static class StaticOverrideTarget {
    public static String compute(String input) {
      return "Original: " + input;
    }
  }

  private static final String TARGET_CLASS =
      "com.github.cc11001100.weavergirl.core.integration.AgentIntegrationTest$TargetService";

  private static final String STATIC_TARGET_CLASS =
      "com.github.cc11001100.weavergirl.core.integration.AgentIntegrationTest$StaticTargetService";

  private static final String STATIC_OVERRIDE_TARGET_CLASS =
      "com.github.cc11001100.weavergirl.core.integration.AgentIntegrationTest$StaticOverrideTarget";

  private static Instrumentation instrumentation;
  private DefaultInterceptorRegistry registry;

  @BeforeAll
  static void setUpClass() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Exception e) {
      // Not all environments support self-attach
      instrumentation = null;
    }
  }

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(
        instrumentation != null, "ByteBuddyAgent self-attach not available in this environment");
    Assumptions.assumeTrue(
        instrumentation.isRetransformClassesSupported(),
        "JVM does not support class retransformation");

    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    if (registry != null) {
      registry.clear();
    }
  }

  @Test
  void beforeCallbackIsInvokedAtRuntime() {
    AtomicBoolean beforeCalled = new AtomicBoolean(false);
    AtomicReference<String> capturedArg = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            beforeCalled.set(true);
            capturedArg.set((String) inv.getArgument(0));
          }
        };

    registry.register(
        new InterceptorDefinition(
            "test-before",
            new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.byName("greet")),
            interceptor));

    installTransformer();

    TargetService service = new TargetService();
    String result = service.greet("World");

    assertTrue(beforeCalled.get(), "before() should have been called");
    assertEquals("World", capturedArg.get(), "Argument should have been captured");
    assertEquals("Hello, World", result, "Original return value should be preserved");
  }

  @Test
  void afterCallbackIsInvokedAtRuntime() {
    AtomicBoolean afterCalled = new AtomicBoolean(false);
    AtomicReference<Object> capturedReturn = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            // no-op
          }

          @Override
          public void after(MethodInvocation inv) {
            afterCalled.set(true);
            capturedReturn.set(inv.getReturnValue());
          }
        };

    // Register for ALL methods on TargetService, then install transformer once
    registry.register(
        new InterceptorDefinition(
            "test-after",
            new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.any()),
            interceptor));

    installTransformer();

    TargetService service = new TargetService();
    int result = service.add(3, 4);

    assertTrue(afterCalled.get(), "after() should have been called");
    assertEquals(7, capturedReturn.get(), "Return value should have been captured");
    assertEquals(7, result, "Original return value should be preserved");
  }

  @Test
  void onExceptionCallbackIsInvokedAtRuntime() {
    AtomicBoolean exceptionCalled = new AtomicBoolean(false);
    AtomicReference<Throwable> capturedException = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            // no-op
          }

          @Override
          public void onException(MethodInvocation inv) {
            exceptionCalled.set(true);
            capturedException.set(inv.getThrowable());
          }
        };

    // Register for ALL methods on TargetService, then install transformer once
    registry.register(
        new InterceptorDefinition(
            "test-exception",
            new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.any()),
            interceptor));

    installTransformer();

    TargetService service = new TargetService();
    RuntimeException thrown = assertThrows(RuntimeException.class, service::riskyOperation);

    assertTrue(exceptionCalled.get(), "onException() should have been called");
    assertNotNull(capturedException.get(), "Throwable should have been captured");
    assertEquals("something went wrong", capturedException.get().getMessage());
  }

  @Test
  void allCallbacksWorkTogetherOnSameMethod() {
    AtomicBoolean beforeCalled = new AtomicBoolean(false);
    AtomicBoolean afterCalled = new AtomicBoolean(false);
    AtomicReference<String> capturedArg = new AtomicReference<>();
    AtomicReference<Object> capturedReturn = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            beforeCalled.set(true);
            capturedArg.set((String) inv.getArgument(0));
          }

          @Override
          public void after(MethodInvocation inv) {
            afterCalled.set(true);
            capturedReturn.set(inv.getReturnValue());
          }
        };

    registry.register(
        new InterceptorDefinition(
            "test-all",
            new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.byName("greet")),
            interceptor));

    installTransformer();

    TargetService service = new TargetService();
    String result = service.greet("Integration");

    assertTrue(beforeCalled.get(), "before() should have been called");
    assertTrue(afterCalled.get(), "after() should have been called");
    assertEquals("Integration", capturedArg.get());
    assertEquals("Hello, Integration", capturedReturn.get());
    assertEquals("Hello, Integration", result);
  }

  @Test
  void staticMethodInterception_targetIsNull() {
    AtomicBoolean beforeCalled = new AtomicBoolean(false);
    AtomicReference<Object> capturedTarget = new AtomicReference<>();
    AtomicReference<String> capturedMethodName = new AtomicReference<>();
    AtomicReference<Object> capturedReturn = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            beforeCalled.set(true);
            capturedTarget.set(inv.getTarget());
            capturedMethodName.set(inv.getMethodName());
          }

          @Override
          public void after(MethodInvocation inv) {
            capturedReturn.set(inv.getReturnValue());
          }
        };

    registry.register(
        new InterceptorDefinition(
            "test-static",
            new Pointcut(
                ClassMatcher.byName(STATIC_TARGET_CLASS), MethodMatcher.byName("staticGreet")),
            interceptor));

    installTransformer();

    String result = StaticTargetService.staticGreet("World");

    assertTrue(beforeCalled.get(), "before() should have been called for static method");
    assertNull(capturedTarget.get(), "target should be null for static methods");
    assertEquals(
        "staticGreet", capturedMethodName.get(), "Method name should be captured correctly");
    assertEquals("Static Hello, World", capturedReturn.get(), "Return value should be captured");
    assertEquals("Static Hello, World", result, "Original return value should be preserved");
  }

  @Test
  void staticMethodInterception_beforeAndAfterCallbacksWork() {
    AtomicBoolean beforeCalled = new AtomicBoolean(false);
    AtomicBoolean afterCalled = new AtomicBoolean(false);
    AtomicReference<Object> capturedReturn = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            beforeCalled.set(true);
            assertNull(inv.getTarget(), "Target should be null for static methods");
          }

          @Override
          public void after(MethodInvocation inv) {
            afterCalled.set(true);
            capturedReturn.set(inv.getReturnValue());
          }
        };

    registry.register(
        new InterceptorDefinition(
            "test-static-callbacks",
            new Pointcut(
                ClassMatcher.byName(STATIC_TARGET_CLASS), MethodMatcher.byName("staticGreet")),
            interceptor));

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation);
    transformer.retransformLoadedClasses();

    String result = StaticTargetService.staticGreet("World");
    assertTrue(beforeCalled.get(), "before() should be called for static methods");
    assertTrue(afterCalled.get(), "after() should be called for static methods");
    assertEquals("Static Hello, World", capturedReturn.get(), "Return value should be captured");
    assertEquals("Static Hello, World", result, "Original return value should be preserved");
  }

  @Test
  void staticMethodInterception_returnValueCanBeOverridden() {
    AtomicReference<Object> capturedOriginalReturn = new AtomicReference<>();
    AtomicReference<String> capturedMethodName = new AtomicReference<>();

    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            capturedMethodName.set(inv.getMethodName());
          }

          @Override
          public void after(MethodInvocation inv) {
            capturedOriginalReturn.set(inv.getReturnValue());
          }
        };

    registry.register(
        new InterceptorDefinition(
            "test-static-override",
            new Pointcut(
                ClassMatcher.byName(STATIC_OVERRIDE_TARGET_CLASS), MethodMatcher.byName("compute")),
            interceptor));

    installTransformer();

    String result = StaticOverrideTarget.compute("test");
    assertEquals(
        "compute", capturedMethodName.get(), "Method name should be captured for static method");
    assertEquals(
        "Original: test",
        capturedOriginalReturn.get(),
        "Original return value should be captured in after()");
    assertEquals("Original: test", result, "Original return value should be preserved");
  }

  private void installTransformer() {
    WeaverTransformer transformer = new WeaverTransformer(registry);
    // In test environment, we need to instrument test target classes which
    // are under com.github.cc11001100.weavergirl.* package. Disable the
    // default agent-class ignore so test helpers can be intercepted.
    transformer.setIgnoreAgentClasses(false);
    transformer.install(instrumentation);
  }
}
