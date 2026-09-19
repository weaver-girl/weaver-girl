package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.AfterReturning;
import com.github.cc11001100.weavergirl.annotation.Arg;
import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.Elapsed;
import com.github.cc11001100.weavergirl.annotation.NotNull;
import com.github.cc11001100.weavergirl.annotation.Origin;
import com.github.cc11001100.weavergirl.annotation.Return;
import com.github.cc11001100.weavergirl.annotation.This;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @Arg}, {@code @Elapsed}, {@code @NotNull}, {@code @Origin}, {@code @Return},
 * and {@code @This} parameter-injection annotations are actually resolved when {@link
 * AnnotationPluginLoader} reflectively invokes advice methods, instead of every advice method
 * silently only ever receiving the raw {@link MethodInvocation}.
 */
class AdviceParameterInjectionTest {

  static class TargetInstance {}

  @WeaveClass(target = "com.example.ParamInjectionService")
  public static class ParamInjectionInterceptor {
    static final List<Object> captured = Collections.synchronizedList(new ArrayList<>());

    @Before("save")
    public void beforeSave(MethodInvocation inv, @Arg(0) String username, @Arg(1) int age) {
      captured.add("before:" + username + ":" + age);
    }

    @Before("save")
    public void beforeWithOrigin(@Origin Method method, @This Object target) {
      captured.add("origin:" + method.getName() + ":" + (target != null));
    }

    @After("save")
    public void afterElapsed(MethodInvocation inv, @Elapsed long nanos) {
      captured.add("elapsed:" + (nanos >= 0));
    }

    @AfterReturning("save")
    public void afterReturningValue(@Return String result) {
      captured.add("return:" + result);
    }

    @Before("guarded")
    public void guarded(@NotNull(message = "arg0 must not be null") @Arg(0) String value) {
      captured.add("guarded:" + value);
    }
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
    ParamInjectionInterceptor.captured.clear();
  }

  private void load() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(ParamInjectionInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor interceptorFor(String methodName) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + methodName))
        .getInterceptor();
  }

  @Test
  void argAnnotation_injectsIndexedMethodArgument() {
    load();
    Interceptor interceptor = interceptorFor("save");
    MethodInvocation inv =
        new MethodInvocation(
            ParamInjectionInterceptor.class, "save", "target", new Object[] {"alice", 30});
    interceptor.before(inv);

    assertTrue(ParamInjectionInterceptor.captured.contains("before:alice:30"));
  }

  @Test
  void originAndThisAnnotations_injectMethodAndTargetInstance() throws Exception {
    load();
    Interceptor interceptor = interceptorFor("save");
    Method origin = Object.class.getMethod("toString");
    TargetInstance targetInstance = new TargetInstance();
    MethodInvocation inv =
        new MethodInvocation(
            ParamInjectionInterceptor.class, "save", origin, targetInstance, new Object[0]);
    interceptor.before(inv);

    assertTrue(ParamInjectionInterceptor.captured.contains("origin:toString:true"));
  }

  @Test
  void elapsedAnnotation_receivesNonNegativeNanosCapturedSinceBefore() {
    load();
    Interceptor interceptor = interceptorFor("save");
    MethodInvocation inv =
        new MethodInvocation(ParamInjectionInterceptor.class, "save", "target", new Object[0]);
    interceptor.before(inv);
    interceptor.after(inv);

    assertTrue(ParamInjectionInterceptor.captured.contains("elapsed:true"));
  }

  @Test
  void returnAnnotation_injectsReturnValueInAfterReturning() {
    load();
    Interceptor interceptor = interceptorFor("save");
    MethodInvocation inv =
        new MethodInvocation(ParamInjectionInterceptor.class, "save", "target", new Object[0]);
    inv.initReturnValue("saved-ok");
    interceptor.after(inv);

    assertTrue(ParamInjectionInterceptor.captured.contains("return:saved-ok"));
  }

  @Test
  void notNullAnnotation_allowsNonNullArgumentThrough() {
    load();
    Interceptor interceptor = interceptorFor("guarded");
    MethodInvocation inv =
        new MethodInvocation(
            ParamInjectionInterceptor.class, "guarded", "target", new Object[] {"present"});
    interceptor.before(inv);

    assertTrue(ParamInjectionInterceptor.captured.contains("guarded:present"));
  }

  @Test
  void notNullAnnotation_doesNotPropagateExceptionWhenArgumentIsNull() {
    // Advice invocation failures are swallowed (logged) by the framework, matching how every
    // other reflective advice invocation already behaves -- a misbehaving interceptor must not
    // be able to break the target application.
    load();
    Interceptor interceptor = interceptorFor("guarded");
    MethodInvocation inv =
        new MethodInvocation(
            ParamInjectionInterceptor.class, "guarded", "target", new Object[] {null});

    assertDoesNotThrow(() -> interceptor.before(inv));
    assertFalse(ParamInjectionInterceptor.captured.stream().anyMatch(c -> c.equals("guarded:null")));
  }
}
