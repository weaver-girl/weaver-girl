package com.github.cc11001100.weavergirl.api;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ContextExecutor;
import com.github.cc11001100.weavergirl.api.context.ContextExecutorService;
import com.github.cc11001100.weavergirl.api.context.ContextPropagator;
import com.github.cc11001100.weavergirl.api.context.ContextPropagatorRegistry;
import com.github.cc11001100.weavergirl.api.context.ContextPropagators;
import com.github.cc11001100.weavergirl.api.context.ContextScheduledExecutorService;
import com.github.cc11001100.weavergirl.api.context.ContextScope;
import com.github.cc11001100.weavergirl.api.context.ContextSnapshot;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/**
 * API Stability Test — verifies that all public API methods exist with expected signatures. This
 * test acts as a safeguard against accidental API breakage between releases.
 */
class ApiStabilityTest {

  // ===== WeaverPlugin interface =====

  @Test
  void weaverPlugin_hasRequiredMethods() throws NoSuchMethodException {
    // Required
    WeaverPlugin.class.getMethod("name");
    WeaverPlugin.class.getMethod("registerInterceptors", InterceptorRegistry.class);

    // Optional with defaults
    WeaverPlugin.class.getMethod("init", PluginContext.class);
    WeaverPlugin.class.getMethod("depends");
    WeaverPlugin.class.getMethod("destroy");
    WeaverPlugin.class.getMethod("isEnabled", PluginContext.class);
  }

  @Test
  void weaverPlugin_isEnabled_returnsTrueByDefault() {
    WeaverPlugin plugin =
        new WeaverPlugin() {
          @Override
          public String name() {
            return "test";
          }

          @Override
          public void registerInterceptors(InterceptorRegistry r) {}
        };
    // Default implementation should return true
    assertTrue(plugin.isEnabled(null));
  }

  // ===== Interceptor interface =====

  @Test
  void interceptor_hasThreeCallbacks() throws NoSuchMethodException {
    Interceptor.class.getMethod("before", MethodInvocation.class);
    Interceptor.class.getMethod("after", MethodInvocation.class);
    Interceptor.class.getMethod("onException", MethodInvocation.class);
  }

  // ===== InterceptorDefinition =====

  @Test
  void interceptorDefinition_constructorExists() throws NoSuchMethodException {
    Constructor<InterceptorDefinition> ctor =
        InterceptorDefinition.class.getConstructor(
            String.class, Pointcut.class, Interceptor.class, int.class);
    assertNotNull(ctor);
  }

  @Test
  void interceptorDefinition_gettersExist() throws NoSuchMethodException {
    InterceptorDefinition.class.getMethod("getName");
    InterceptorDefinition.class.getMethod("getPointcut");
    InterceptorDefinition.class.getMethod("getInterceptor");
    InterceptorDefinition.class.getMethod("getPriority");
  }

  @Test
  void interceptorDefinition_rejectsInvalidInput() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InterceptorDefinition(
                null,
                new Pointcut(ClassMatcher.byName("X"), MethodMatcher.any()),
                new Interceptor() {},
                0));

    assertThrows(
        IllegalArgumentException.class,
        () -> new InterceptorDefinition("test", null, new Interceptor() {}, 0));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InterceptorDefinition(
                "test", new Pointcut(ClassMatcher.byName("X"), MethodMatcher.any()), null, 0));
  }

  // ===== ClassMatcher =====

  @Test
  void classMatcher_factoryMethods() {
    assertNotNull(ClassMatcher.byName("com.example.Service"));
    assertNotNull(ClassMatcher.byNamePattern("com\\.example\\..*"));
    assertNotNull(ClassMatcher.byAnnotation("com.example.Trace"));
    assertNotNull(ClassMatcher.bySuperClass("com.example.Base"));
    assertNotNull(ClassMatcher.byInterface("java.io.Serializable"));
  }

  @Test
  void classMatcher_rejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> ClassMatcher.byName(null));
    assertThrows(IllegalArgumentException.class, () -> ClassMatcher.byName(""));
    assertThrows(IllegalArgumentException.class, () -> ClassMatcher.byNamePattern(null));
  }

  @Test
  void classMatcher_matchesWorks() {
    ClassMatcher matcher = ClassMatcher.byName("com.example.Service");
    assertTrue(matcher.matches("com.example.Service"));
    assertFalse(matcher.matches("com.other.Service"));
  }

  // ===== MethodMatcher =====

  @Test
  void methodMatcher_factoryMethods() {
    assertNotNull(MethodMatcher.byName("execute"));
    assertNotNull(MethodMatcher.byNamePattern("exec.*"));
    assertNotNull(MethodMatcher.byAnnotation("com.example.Traced"));
    assertNotNull(MethodMatcher.bySignature("execute", "java.lang.String,int"));
    assertNotNull(MethodMatcher.any());
  }

  @Test
  void methodMatcher_rejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byName(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.byNamePattern(null));
    assertThrows(IllegalArgumentException.class, () -> MethodMatcher.bySignature(null, "x"));
  }

  // ===== Pointcut =====

  @Test
  void pointcut_compositionWorks() {
    Pointcut p =
        new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("execute"));
    assertNotNull(p.getClassMatcher());
    assertNotNull(p.getMethodMatcher());
  }

  // ===== InterceptorEvent =====

  @Test
  void interceptorEvent_builderPattern() {
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("slow-query")
            .plugin("jdbc")
            .className("Stmt")
            .methodName("execute")
            .durationMs(1500)
            .attribute("sql", "SELECT 1")
            .build();

    assertEquals("slow-query", event.getType());
    assertEquals("jdbc", event.getPlugin());
    assertEquals("Stmt", event.getClassName());
    assertEquals("execute", event.getMethodName());
    assertEquals(1500, event.getDurationMs());
    assertEquals("SELECT 1", event.getAttributes().get("sql"));
  }

  @Test
  void interceptorEvent_toMap() {
    InterceptorEvent event = InterceptorEvent.builder().type("test").plugin("p").build();
    Map<String, Object> map = event.toMap();
    assertEquals("test", map.get("type"));
    assertEquals("p", map.get("plugin"));
  }

  // ===== InterceptorEventPublisher =====

  @Test
  void eventPublisher_singletonAccess() {
    InterceptorEventPublisher pub1 = InterceptorEventPublisher.getInstance();
    InterceptorEventPublisher pub2 = InterceptorEventPublisher.getInstance();
    assertSame(pub1, pub2);
  }

  @Test
  void eventPublisher_addRemoveListener() {
    InterceptorEventPublisher pub = InterceptorEventPublisher.getInstance();
    InterceptorEventListener listener = event -> {};
    pub.addListener(listener);
    assertTrue(pub.getListeners().contains(listener));
    pub.removeListener(listener);
    assertFalse(pub.getListeners().contains(listener));
  }

  // ===== PluginContext =====

  @Test
  void pluginContext_typedConfigGetters() {
    // Verify PluginContext interface has typed getter methods
    try {
      PluginContext.class.getMethod("getConfig", String.class, String.class);
      PluginContext.class.getMethod("getConfigLong", String.class, Long.TYPE);
      PluginContext.class.getMethod("getConfigBoolean", String.class, Boolean.TYPE);
      PluginContext.class.getMethod("getConfigEnum", String.class, String.class, String[].class);
    } catch (NoSuchMethodException e) {
      fail("PluginContext missing expected method: " + e.getMessage());
    }
  }

  // ===== ThreadContext =====

  @Test
  void threadContext_hasRequiredMethods() throws NoSuchMethodException {
    ThreadContext.class.getMethod("get", String.class);
    ThreadContext.class.getMethod("put", String.class, Object.class);
    ThreadContext.class.getMethod("putAll", Map.class);
    ThreadContext.class.getMethod("remove", String.class);
    ThreadContext.class.getMethod("containsKey", String.class);
    ThreadContext.class.getMethod("clear");
    ThreadContext.class.getMethod("capture");
    ThreadContext.class.getMethod("restore", Map.class);
    ThreadContext.class.getMethod("snapshot");
  }

  @Test
  void contextPropagation_hasRequiredMethods() throws NoSuchMethodException {
    ContextSnapshot.class.getMethod("capture");
    ContextSnapshot.class.getMethod("empty");
    ContextSnapshot.class.getMethod("fromThreadContext", Map.class);
    ContextSnapshot.class.getMethod("getThreadContext");
    ContextSnapshot.class.getMethod("activate");
    ContextScope.class.getMethod("activate", ContextSnapshot.class);
    ContextScope.class.getMethod("close");

    // ContextPropagator SPI interface
    ContextPropagator.class.getMethod("name");
    ContextPropagator.class.getMethod("capture");
    ContextPropagator.class.getMethod("restore", ContextPropagator.Snapshot.class);
    ContextPropagator.class.getMethod("cleanup", ContextPropagator.Snapshot.class);

    // ContextPropagators static facade (renamed from old ContextPropagator)
    ContextPropagators.class.getMethod("capture");
    ContextPropagators.class.getMethod("scope", ContextSnapshot.class);
    ContextPropagators.class.getMethod("wrap", Runnable.class);
    ContextPropagators.class.getMethod("wrap", Runnable.class, ContextSnapshot.class);
    ContextPropagators.class.getMethod("wrap", Callable.class);
    ContextPropagators.class.getMethod("wrap", Callable.class, ContextSnapshot.class);
    ContextPropagators.class.getMethod("wrapCallables", Collection.class);
    ContextPropagators.class.getMethod("wrapCallables", Collection.class, ContextSnapshot.class);

    // ContextPropagatorRegistry
    ContextPropagatorRegistry.class.getMethod("register", ContextPropagator.class);
    ContextPropagatorRegistry.class.getMethod("unregister", String.class);
    ContextPropagatorRegistry.class.getMethod("getAll");
    ContextPropagatorRegistry.class.getMethod("get", String.class);
    ContextExecutor.class.getConstructor(Executor.class);
    ContextExecutor.class.getMethod("execute", Runnable.class);
    ContextExecutor.class.getMethod("getDelegate");
    ContextExecutor.class.getMethod("wrap", Executor.class);
    ContextExecutorService.class.getConstructor(ExecutorService.class);
    ContextExecutorService.class.getMethod("getDelegate");
    ContextExecutorService.class.getMethod("wrap", ExecutorService.class);
    ContextExecutorService.class.getMethod("execute", Runnable.class);
    ContextExecutorService.class.getMethod("submit", Callable.class);
    ContextExecutorService.class.getMethod("submit", Runnable.class);
    ContextExecutorService.class.getMethod("submit", Runnable.class, Object.class);
    ContextExecutorService.class.getMethod("invokeAll", Collection.class);
    ContextExecutorService.class.getMethod(
        "invokeAll", Collection.class, Long.TYPE, TimeUnit.class);
    ContextExecutorService.class.getMethod("invokeAny", Collection.class);
    ContextExecutorService.class.getMethod(
        "invokeAny", Collection.class, Long.TYPE, TimeUnit.class);
    ContextScheduledExecutorService.class.getConstructor(ScheduledExecutorService.class);
    ContextScheduledExecutorService.class.getMethod("getDelegate");
    ContextScheduledExecutorService.class.getMethod("wrap", ScheduledExecutorService.class);
    ContextScheduledExecutorService.class.getMethod(
        "schedule", Runnable.class, Long.TYPE, TimeUnit.class);
    ContextScheduledExecutorService.class.getMethod(
        "schedule", Callable.class, Long.TYPE, TimeUnit.class);
    ContextScheduledExecutorService.class.getMethod(
        "scheduleAtFixedRate", Runnable.class, Long.TYPE, Long.TYPE, TimeUnit.class);
    ContextScheduledExecutorService.class.getMethod(
        "scheduleWithFixedDelay", Runnable.class, Long.TYPE, Long.TYPE, TimeUnit.class);
  }

  // ===== MethodInvocation =====

  @Test
  void methodInvocation_hasRequiredMethods() throws NoSuchMethodException {
    MethodInvocation.class.getMethod("getTargetClass");
    MethodInvocation.class.getMethod("getMethodName");
    MethodInvocation.class.getMethod("getArguments");
    MethodInvocation.class.getMethod("getReturnType");
    MethodInvocation.class.getMethod("getReturnValue");
    MethodInvocation.class.getMethod("setReturnValue", Object.class);
    MethodInvocation.class.getMethod("skipMethod");
    MethodInvocation.class.getMethod("isSkipped");
  }

  // ===== InterceptorRegistry =====

  @Test
  void interceptorRegistry_hasRegisterMethod() throws NoSuchMethodException {
    InterceptorRegistry.class.getMethod("register", InterceptorDefinition.class);
  }
}
