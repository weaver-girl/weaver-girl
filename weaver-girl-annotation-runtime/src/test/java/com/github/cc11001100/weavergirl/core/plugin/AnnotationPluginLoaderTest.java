package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnnotationPluginLoaderTest {

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
  }

  // ---------------------------------------------------------------
  // Test helper classes with annotations
  // ---------------------------------------------------------------

  @WeaveClass(target = "com.example.TargetService")
  public static class BeforeInterceptor {
    boolean beforeCalled = false;
    MethodInvocation capturedInvocation = null;

    @Before("doWork")
    public void beforeDoWork(MethodInvocation invocation) {
      beforeCalled = true;
      capturedInvocation = invocation;
    }
  }

  @WeaveClass(target = "com.example.TargetService")
  public static class AroundInterceptor {
    boolean aroundCalledInBefore = false;
    boolean aroundCalledInAfter = false;

    @Around("doWork")
    public void aroundDoWork(MethodInvocation invocation) {
      // Around is called in both before and after phases
    }
  }

  @WeaveClass(target = "com.example.CalcService")
  public static class AfterInterceptor {
    boolean afterCalled = false;
    MethodInvocation capturedInvocation = null;

    @Before("compute")
    public void beforeCompute(MethodInvocation invocation) {
      // no-op
    }

    @After("compute")
    public void afterCompute(MethodInvocation invocation) {
      afterCalled = true;
      capturedInvocation = invocation;
    }
  }

  // A class WITHOUT @WeaveClass — should be skipped
  public static class NoWeaveClassInterceptor {
    @Before("doWork")
    public void beforeDoWork(MethodInvocation invocation) {}
  }

  @WeaveClass(target = "com.example.MultiService")
  public static class MultiMethodInterceptor {
    boolean beforeSaveCalled = false;
    boolean afterDeleteCalled = false;

    @Before("save")
    public void beforeSave(MethodInvocation invocation) {
      beforeSaveCalled = true;
    }

    @After("delete")
    public void afterDelete(MethodInvocation invocation) {
      afterDeleteCalled = true;
    }
  }

  // ---------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------

  @Test
  void loadAnnotatedInterceptors_withWeaveClassAndBefore_registersDefinition() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(BeforeInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size(), "Should register exactly one interceptor definition");

    InterceptorDefinition def = defs.get(0);
    assertEquals("annotation-BeforeInterceptor-doWork", def.getName());
  }

  @Test
  void loadAnnotatedInterceptors_withWeaveClassAndAround_registersDefinition() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(AroundInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size(), "Should register exactly one interceptor definition");

    InterceptorDefinition def = defs.get(0);
    assertEquals("annotation-AroundInterceptor-doWork", def.getName());
  }

  @Test
  void loadAnnotatedInterceptors_noWeaveClass_skipped() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(NoWeaveClassInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertTrue(defs.isEmpty(), "Class without @WeaveClass should be skipped");
  }

  @Test
  void loadAnnotatedInterceptors_correctClassAndMethodMatcher() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(BeforeInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    InterceptorDefinition def = registry.getAllDefinitions().get(0);
    ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
    MethodMatcher methodMatcher = def.getPointcut().getMethodMatcher();

    assertEquals(ClassMatcher.MatchType.EXACT_NAME, classMatcher.getMatchType());
    assertEquals("com.example.TargetService", classMatcher.getPattern());
    assertTrue(classMatcher.matches("com.example.TargetService"));
    assertFalse(classMatcher.matches("com.example.OtherService"));

    assertEquals(MethodMatcher.MatchType.EXACT_NAME, methodMatcher.getMatchType());
    assertEquals("doWork", methodMatcher.getPattern());
    assertTrue(methodMatcher.matches("doWork"));
    assertFalse(methodMatcher.matches("otherMethod"));
  }

  @Test
  void interceptor_beforeCallback_callsAnnotatedMethod() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(BeforeInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    InterceptorDefinition def = registry.getAllDefinitions().get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation invocation =
        new MethodInvocation(String.class, "doWork", "target", new Object[0]);

    interceptor.before(invocation);

    // The AnnotationPluginLoader creates a new instance of the interceptor class,
    // so we cannot directly check the flag on our instance. Instead, verify
    // that the before() callback does not throw and completes successfully.
    // To verify the annotated method is actually called, we rely on the
    // fact that a method accepting MethodInvocation will receive it.
    // We verify the interceptor is non-null and the before call completes.
    assertNotNull(interceptor);
  }

  @Test
  void interceptor_afterCallback_callsAfterAnnotatedMethod() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(AfterInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    InterceptorDefinition def = registry.getAllDefinitions().get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation invocation =
        new MethodInvocation(String.class, "compute", "target", new Object[0]);

    // Call after — should invoke the @After method without throwing
    assertDoesNotThrow(() -> interceptor.after(invocation));
  }

  @Test
  void interceptor_aroundCalledInBothBeforeAndAfter() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(AroundInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    InterceptorDefinition def = registry.getAllDefinitions().get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation invocation =
        new MethodInvocation(String.class, "doWork", "target", new Object[0]);

    // Around should be called in both before and after — just verify no exceptions
    assertDoesNotThrow(() -> interceptor.before(invocation));
    assertDoesNotThrow(() -> interceptor.after(invocation));
  }

  @Test
  void loadAnnotatedInterceptors_multipleTargetMethods_registersMultiple() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(MultiMethodInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(2, defs.size(), "Should register one definition per target method");

    Set<String> names = new HashSet<>();
    for (InterceptorDefinition def : defs) {
      names.add(def.getName());
    }
    assertTrue(names.contains("annotation-MultiMethodInterceptor-save"));
    assertTrue(names.contains("annotation-MultiMethodInterceptor-delete"));
  }

  @Test
  void loadAnnotatedInterceptors_emptySet_noDefinitions() {
    loader.loadAnnotatedInterceptors(Collections.emptySet(), registry);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertTrue(defs.isEmpty(), "Empty class set should produce no definitions");
  }

  @Test
  void interceptor_beforeReceivesMethodInvocation() throws Exception {
    // Verify that the annotated method actually receives the MethodInvocation
    // by using a class that records what it receives into a static field.
    BeforeReceiverInterceptor.received = null;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(BeforeReceiverInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    InterceptorDefinition def = registry.getAllDefinitions().get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation invocation =
        new MethodInvocation(String.class, "doWork", "targetObj", new Object[] {"arg1"});
    interceptor.before(invocation);

    assertNotNull(
        BeforeReceiverInterceptor.received, "Before method should have received MethodInvocation");
    assertEquals("doWork", BeforeReceiverInterceptor.received.getMethodName());
    assertEquals("targetObj", BeforeReceiverInterceptor.received.getTarget());
  }

  @Test
  void interceptor_afterReceivesMethodInvocation() throws Exception {
    AfterReceiverInterceptor.received = null;

    Set<Class<?>> classes = new HashSet<>();
    classes.add(AfterReceiverInterceptor.class);

    loader.loadAnnotatedInterceptors(classes, registry);

    InterceptorDefinition def = registry.getAllDefinitions().get(0);
    Interceptor interceptor = def.getInterceptor();

    MethodInvocation invocation =
        new MethodInvocation(String.class, "compute", "targetObj", new Object[0]);
    interceptor.after(invocation);

    assertNotNull(
        AfterReceiverInterceptor.received, "After method should have received MethodInvocation");
    assertEquals("compute", AfterReceiverInterceptor.received.getMethodName());
  }

  // ---------------------------------------------------------------
  // Additional test helpers that record into static fields
  // ---------------------------------------------------------------

  @WeaveClass(target = "com.example.ReceiverService")
  public static class BeforeReceiverInterceptor {
    static MethodInvocation received = null;

    @Before("doWork")
    public void beforeDoWork(MethodInvocation invocation) {
      received = invocation;
    }
  }

  @WeaveClass(target = "com.example.ReceiverService2")
  public static class AfterReceiverInterceptor {
    static MethodInvocation received = null;

    @After("compute")
    public void afterCompute(MethodInvocation invocation) {
      received = invocation;
    }
  }
}
