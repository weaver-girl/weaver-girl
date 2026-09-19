package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.AfterFinally;
import com.github.cc11001100.weavergirl.annotation.AfterThrowing;
import com.github.cc11001100.weavergirl.annotation.OnException;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks in the {@code @AfterFinally} / {@code @AfterThrowing} dispatch contract in {@link
 * AnnotationPluginLoader#createReflectiveInterceptor}:
 *
 * <ul>
 *   <li>{@code @AfterFinally} fires via {@code afterFinally()} on success, on exception, and on
 *       skip — never via {@code after()} or {@code onException()}.
 *   <li>{@code @AfterThrowing} fires via {@code onException()} with exception-type filtering — never
 *       via {@code after()}.
 * </ul>
 */
class AfterFinallyAndThrowingTest {

  @WeaveClass(target = "com.example.FinallyService")
  public static class FinallyInterceptor {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @AfterFinally("process")
    public void cleanup(MethodInvocation inv) {
      calls.add("finally");
    }
  }

  @WeaveClass(target = "com.example.ThrowingService")
  public static class ThrowingInterceptor {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @AfterThrowing(value = "save", exceptionType = IOException.class)
    public void onIoError(MethodInvocation inv) {
      calls.add("afterThrowing-io");
    }

    @AfterThrowing("save")
    public void onAnyError(MethodInvocation inv) {
      calls.add("afterThrowing-any");
    }

    @OnException("save")
    public void onError(MethodInvocation inv) {
      calls.add("onException");
    }
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
    FinallyInterceptor.calls.clear();
    ThrowingInterceptor.calls.clear();
  }

  private Interceptor loadSingle(Class<?> clazz) {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(clazz);
    loader.loadAnnotatedInterceptors(classes, registry);
    assertEquals(1, registry.getAllDefinitions().size());
    return registry.getAllDefinitions().get(0).getInterceptor();
  }

  // ========== @AfterFinally ==========

  @Test
  void afterFinally_firesOnSuccess() {
    Interceptor interceptor = loadSingle(FinallyInterceptor.class);
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);
    inv.initReturnValue("ok");
    interceptor.after(inv);
    assertFalse(FinallyInterceptor.calls.contains("finally"),
        "@AfterFinally must NOT fire via after()");
    interceptor.afterFinally(inv);
    assertEquals(List.of("finally"), FinallyInterceptor.calls);
  }

  @Test
  void afterFinally_firesOnException() {
    Interceptor interceptor = loadSingle(FinallyInterceptor.class);
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);
    inv.setThrowable(new RuntimeException("boom"));
    interceptor.onException(inv);
    assertFalse(FinallyInterceptor.calls.contains("finally"),
        "@AfterFinally must NOT fire via onException()");
    interceptor.afterFinally(inv);
    assertEquals(List.of("finally"), FinallyInterceptor.calls);
  }

  @Test
  void afterFinally_firesOnSkip() {
    Interceptor interceptor = loadSingle(FinallyInterceptor.class);
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);
    inv.skipMethod();
    interceptor.afterFinally(inv);
    assertEquals(List.of("finally"), FinallyInterceptor.calls,
        "@AfterFinally must fire even when the method was skipped");
  }

  // ========== @AfterThrowing ==========

  @Test
  void afterThrowing_firesOnException_notOnSuccess() {
    Interceptor interceptor = loadSingle(ThrowingInterceptor.class);

    // Success path: neither @AfterThrowing nor @OnException fires
    MethodInvocation success = new MethodInvocation(String.class, "save", "target", new Object[0]);
    success.initReturnValue("ok");
    interceptor.after(success);
    assertTrue(ThrowingInterceptor.calls.isEmpty(),
        "@AfterThrowing must NOT fire via after() on success");

    // Exception path: filtered + unfiltered @AfterThrowing and @OnException all fire
    ThrowingInterceptor.calls.clear();
    MethodInvocation failure = new MethodInvocation(String.class, "save", "target", new Object[0]);
    failure.setThrowable(new IOException("disk full"));
    interceptor.onException(failure);
    assertTrue(ThrowingInterceptor.calls.contains("afterThrowing-io"));
    assertTrue(ThrowingInterceptor.calls.contains("afterThrowing-any"));
    assertTrue(ThrowingInterceptor.calls.contains("onException"));
  }

  @Test
  void afterThrowing_exceptionTypeFilter_skipsNonMatching() {
    Interceptor interceptor = loadSingle(ThrowingInterceptor.class);
    MethodInvocation inv = new MethodInvocation(String.class, "save", "target", new Object[0]);
    inv.setThrowable(new IllegalArgumentException("bad arg"));
    interceptor.onException(inv);
    assertFalse(ThrowingInterceptor.calls.contains("afterThrowing-io"),
        "IOException-filtered @AfterThrowing must skip IllegalArgumentException");
    assertTrue(ThrowingInterceptor.calls.contains("afterThrowing-any"),
        "unfiltered @AfterThrowing must still fire");
    assertTrue(ThrowingInterceptor.calls.contains("onException"));
  }

  @Test
  void afterThrowing_subclassException_matchesFilter() {
    Interceptor interceptor = loadSingle(ThrowingInterceptor.class);
    MethodInvocation inv = new MethodInvocation(String.class, "save", "target", new Object[0]);
    // java.io.FileNotFoundException extends IOException
    inv.setThrowable(new java.io.FileNotFoundException("missing"));
    interceptor.onException(inv);
    assertTrue(ThrowingInterceptor.calls.contains("afterThrowing-io"),
        "exception filter must match subclasses");
  }
}
