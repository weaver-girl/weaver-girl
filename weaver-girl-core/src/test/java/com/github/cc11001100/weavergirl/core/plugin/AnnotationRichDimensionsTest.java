package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the rich annotation dimensions: - MethodInvocation attachments (state passing) -
 * parameterTypes (method overloading) - exceptionType filtering on @OnException - @OnMethodPattern
 * (regex method matching) - @WhenAnnotated (annotation-based method matching) - @OnConstructor
 * - @EnableIf (conditional activation) - @SampleRate - @Timed - @RetryOnException
 */
class AnnotationRichDimensionsTest {

  private AnnotationPluginLoader loader;
  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new DefaultInterceptorRegistry();
  }

  // ========== MethodInvocation Attachments ==========

  @Test
  void attachments_shouldPassStateBetweenBeforeAndAfter() {
    MethodInvocation inv = new MethodInvocation(String.class, "test", "target", new Object[0]);

    // Set in before
    inv.setAttachment("startTime", 42L);
    inv.setAttachment("label", "testMethod");

    // Read in after
    assertEquals(42L, inv.getAttachment("startTime"));
    assertEquals("testMethod", inv.getAttachment("label"));
    assertEquals(42L, inv.getAttachment("startTime", Long.class));

    // hasAttachment / removeAttachment
    assertTrue(inv.hasAttachment("startTime"));
    inv.removeAttachment("startTime");
    assertFalse(inv.hasAttachment("startTime"));
    assertNull(inv.getAttachment("startTime"));
  }

  @Test
  void attachments_shouldBeClearedOnReset() {
    MethodInvocation inv = new MethodInvocation(String.class, "test", "target", new Object[0]);
    inv.setAttachment("key", "value");
    assertTrue(inv.hasAttachment("key"));

    inv.reset(String.class, "test2", null, "target", new Object[0]);
    assertFalse(inv.hasAttachment("key"), "Attachments should be cleared on reset");
  }

  @Test
  void attachments_shouldBeClearedOnClear() {
    MethodInvocation inv = new MethodInvocation(String.class, "test", "target", new Object[0]);
    inv.setAttachment("key", "value");
    inv.clear();
    assertFalse(inv.hasAttachment("key"), "Attachments should be cleared on clear");
  }

  // ========== parameterTypes (Method Overloading) ==========

  @WeaveClass(target = "com.example.OverloadedService")
  public static class OverloadedInterceptor {
    static boolean stringVersionCalled = false;
    static boolean intVersionCalled = false;

    @Before(
        value = "save",
        parameterTypes = {"java.lang.String"})
    public void beforeSaveString(MethodInvocation inv) {
      stringVersionCalled = true;
    }

    @Before(
        value = "save",
        parameterTypes = {"int"})
    public void beforeSaveInt(MethodInvocation inv) {
      intVersionCalled = true;
    }
  }

  @Test
  void parameterTypes_shouldCreateSeparateInterceptorsForOverloads() {
    OverloadedInterceptor.stringVersionCalled = false;
    OverloadedInterceptor.intVersionCalled = false;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(OverloadedInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    // Should have 2 definitions: one for save(String), one for save(int)
    assertEquals(2, defs.size(), "Should create separate interceptors for overloaded methods");

    // Verify definition names contain the signature
    boolean hasStringVersion =
        defs.stream().anyMatch(d -> d.getName().contains("save(java.lang.String)"));
    boolean hasIntVersion = defs.stream().anyMatch(d -> d.getName().contains("save(int)"));
    assertTrue(hasStringVersion, "Should have save(String) interceptor");
    assertTrue(hasIntVersion, "Should have save(int) interceptor");
  }

  // ========== exceptionType Filtering ==========

  @WeaveClass(target = "com.example.FilteredService")
  public static class FilteredExceptionInterceptor {
    static boolean ioExceptionHandlerCalled = false;
    static boolean allExceptionHandlerCalled = false;

    @OnException(value = "process", exceptionType = IOException.class)
    public void handleIO(MethodInvocation inv) {
      ioExceptionHandlerCalled = true;
    }

    @OnException(value = "process")
    public void handleAny(MethodInvocation inv) {
      allExceptionHandlerCalled = true;
    }
  }

  @Test
  void exceptionType_shouldFilterByExceptionType() {
    FilteredExceptionInterceptor.ioExceptionHandlerCalled = false;
    FilteredExceptionInterceptor.allExceptionHandlerCalled = false;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(FilteredExceptionInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    // IOException — both handlers should fire (IOException matches both)
    inv.setThrowable(new IOException("io error"));
    interceptor.onException(inv);
    assertTrue(
        FilteredExceptionInterceptor.ioExceptionHandlerCalled,
        "IOException handler should fire for IOException");
    assertTrue(
        FilteredExceptionInterceptor.allExceptionHandlerCalled,
        "All-exception handler should fire for IOException");
  }

  @Test
  void exceptionType_shouldNotFireForNonMatchingType() {
    FilteredExceptionInterceptor.ioExceptionHandlerCalled = false;
    FilteredExceptionInterceptor.allExceptionHandlerCalled = false;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(FilteredExceptionInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    // RuntimeException — only the all-exception handler should fire
    inv.setThrowable(new RuntimeException("runtime error"));
    interceptor.onException(inv);
    assertFalse(
        FilteredExceptionInterceptor.ioExceptionHandlerCalled,
        "IOException handler should NOT fire for RuntimeException");
    assertTrue(
        FilteredExceptionInterceptor.allExceptionHandlerCalled,
        "All-exception handler should fire for RuntimeException");
  }

  // ========== @OnMethodPattern (Regex Method Matching) ==========

  @WeaveClass(target = "com.example.UserService")
  public static class PatternInterceptor {
    static final List<String> matchedMethods = new ArrayList<>();

    @OnMethodPattern("find.*")
    public void beforeFind(MethodInvocation inv) {
      matchedMethods.add(inv.getMethodName());
    }
  }

  @Test
  void onMethodPattern_shouldRegisterPatternBasedInterceptor() {
    PatternInterceptor.matchedMethods.clear();

    Set<Class<?>> classes = new HashSet<>();
    classes.add(PatternInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertTrue(
        defs.stream().anyMatch(d -> d.getName().contains("pattern")),
        "Should register a pattern-based interceptor");

    Interceptor interceptor =
        defs.stream()
            .filter(d -> d.getName().contains("pattern"))
            .findFirst()
            .get()
            .getInterceptor();

    MethodInvocation inv = new MethodInvocation(String.class, "findById", "target", new Object[0]);
    interceptor.before(inv);
    assertEquals(Collections.singletonList("findById"), PatternInterceptor.matchedMethods);
  }

  // ========== @WhenAnnotated ==========

  @WeaveClass(target = "com.example.AnnotatedService")
  public static class AnnotationMatchInterceptor {
    static boolean annotatedMethodCalled = false;

    @WhenAnnotated("com.example.Monitored")
    public void beforeMonitored(MethodInvocation inv) {
      annotatedMethodCalled = true;
    }
  }

  @Test
  void whenAnnotated_shouldRegisterAnnotationBasedInterceptor() {
    AnnotationMatchInterceptor.annotatedMethodCalled = false;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(AnnotationMatchInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertTrue(
        defs.stream().anyMatch(d -> d.getName().contains("annotated")),
        "Should register an annotation-matched interceptor");
  }

  // ========== @OnConstructor ==========

  @WeaveClass(target = "com.example.ConstructorService")
  public static class ConstructorInterceptor {
    static boolean constructorCalled = false;

    @OnConstructor
    public void onNewInstance(MethodInvocation inv) {
      constructorCalled = true;
    }
  }

  @Test
  void onConstructor_shouldRegisterConstructorInterceptor() {
    ConstructorInterceptor.constructorCalled = false;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(ConstructorInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertTrue(
        defs.stream().anyMatch(d -> d.getName().contains("<init>")),
        "Should register a constructor interceptor");

    Interceptor interceptor =
        defs.stream()
            .filter(d -> d.getName().contains("<init>"))
            .findFirst()
            .get()
            .getInterceptor();

    MethodInvocation inv = new MethodInvocation(String.class, "<init>", "target", new Object[0]);
    inv.initReturnValue("newInstance");
    interceptor.after(inv);
    assertTrue(ConstructorInterceptor.constructorCalled, "Constructor advice should be called");
  }

  // ========== @EnableIf ==========

  @WeaveClass(target = "com.example.ConditionalService")
  @EnableIf(property = "test.enabled", havingValue = "true")
  public static class DisabledInterceptor {
    @Before("process")
    public void beforeProcess(MethodInvocation inv) {}
  }

  @WeaveClass(target = "com.example.AlwaysEnabledService")
  @EnableIf(property = "nonexistent.prop", matchIfMissing = true)
  public static class EnabledByDefaultInterceptor {
    @Before("process")
    public void beforeProcess(MethodInvocation inv) {}
  }

  @Test
  void enableIf_shouldDisableWhenPropertyNotSet() {
    System.clearProperty("test.enabled");

    Set<Class<?>> classes = new HashSet<>();
    classes.add(DisabledInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    assertTrue(
        registry.getAllDefinitions().isEmpty(),
        "Interceptor should be disabled when property not set");
  }

  @Test
  void enableIf_shouldEnableWhenPropertyMatches() {
    System.setProperty("test.enabled", "true");

    try {
      DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
      Set<Class<?>> classes = new HashSet<>();
      classes.add(DisabledInterceptor.class);
      loader.loadAnnotatedInterceptors(classes, reg);

      assertFalse(
          reg.getAllDefinitions().isEmpty(), "Interceptor should be enabled when property matches");
    } finally {
      System.clearProperty("test.enabled");
    }
  }

  @Test
  void enableIf_shouldRespectMatchIfMissing() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(EnabledByDefaultInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    assertFalse(
        registry.getAllDefinitions().isEmpty(),
        "Interceptor should be enabled by matchIfMissing=true when property not set");
  }

  // ========== @SampleRate ==========

  @WeaveClass(target = "com.example.SampledService")
  @SampleRate(0.0) // never sample — effectively disabled
  public static class ZeroSampleInterceptor {
    static int callCount = 0;

    @Before("process")
    public void beforeProcess(MethodInvocation inv) {
      callCount++;
    }
  }

  @Test
  void sampleRate_zero_shouldNotRegisterInterceptor() {
    ZeroSampleInterceptor.callCount = 0;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(ZeroSampleInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    assertTrue(
        registry.getAllDefinitions().isEmpty(),
        "With @SampleRate(0.0), no interceptor should be registered");
  }

  // ========== @Timed ==========

  @WeaveClass(target = "com.example.TimedService")
  @Timed(label = "timedOp", log = false)
  public static class TimedInterceptor {
    @Before("process")
    public void beforeProcess(MethodInvocation inv) {}

    @After("process")
    public void afterProcess(MethodInvocation inv) {}
  }

  @Test
  void timed_shouldRecordElapsedAsAttachment() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(TimedInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue("result");
    interceptor.after(inv);

    assertTrue(inv.hasAttachment("timed.startNanos"), "Should record start time");
    assertTrue(inv.hasAttachment("timed.elapsedNanos"), "Should record elapsed time");
    assertTrue(inv.hasAttachment("timed.elapsedMs"), "Should record elapsed ms");
    assertTrue(inv.getAttachment("timed.elapsedMs", Long.class) >= 0, "Elapsed ms should be >= 0");
  }

  // ========== @RetryOnException ==========

  @WeaveClass(target = "com.example.RetryService")
  public static class RetryInterceptor {
    static final AtomicInteger retryCallbackCount = new AtomicInteger(0);

    @RetryOnException(value = "process", maxRetries = 3, delayMs = 0)
    public void onRetry(MethodInvocation inv) {
      retryCallbackCount.incrementAndGet();
    }
  }

  @Test
  void retryOnException_shouldSuppressExceptionOnFirstAttempts() {
    RetryInterceptor.retryCallbackCount.set(0);

    Set<Class<?>> classes = new HashSet<>();
    classes.add(RetryInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);
    inv.setThrowable(new RuntimeException("transient"));

    interceptor.onException(inv);

    // On first exception, retry logic should suppress and attempt retry
    assertTrue(inv.isExceptionSuppressed(), "Exception should be suppressed for retry");
    assertEquals(1, RetryInterceptor.retryCallbackCount.get(), "Retry callback should fire once");
  }

  // ========== Combined: @Order + @Timed + @SampleRate ==========

  @WeaveClass(target = "com.example.CombinedService")
  @Order(42)
  @Timed(log = false)
  @SampleRate(1.0)
  public static class CombinedInterceptor {
    @Before("execute")
    public void beforeExecute(MethodInvocation inv) {}

    @AfterReturning("execute")
    public void afterExecute(MethodInvocation inv) {}
  }

  @Test
  void combinedAnnotations_allWorkTogether() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(CombinedInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    assertEquals(42, defs.get(0).getPriority(), "@Order(42) should be set");

    Interceptor interceptor = defs.get(0).getInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "execute", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue("ok");
    interceptor.after(inv);

    assertTrue(inv.hasAttachment("timed.elapsedNanos"), "@Timed should record timing");
  }
}
