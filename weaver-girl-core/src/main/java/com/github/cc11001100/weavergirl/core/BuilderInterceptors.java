package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.util.function.Consumer;

/**
 * Reusable Interceptor implementations for WeaverGirl builders.
 */
final class BuilderInterceptors {

  private BuilderInterceptors() {}

  /** Interceptor that delegates before() to a callback. */
  static class BeforeCallbackInterceptor implements Interceptor {
    private final Consumer<MethodInvocation> callback;
    private final Interceptor existing;

    BeforeCallbackInterceptor(Consumer<MethodInvocation> callback, Interceptor existing) {
      this.callback = callback;
      this.existing = existing;
    }

    @Override
    public void before(MethodInvocation invocation) {
      callback.accept(invocation);
    }

    @Override
    public void after(MethodInvocation invocation) {
      if (existing != null) existing.after(invocation);
    }

    @Override
    public void onException(MethodInvocation invocation) {
      if (existing != null) existing.onException(invocation);
    }
  }

  /** Interceptor that delegates after() to a callback. */
  static class AfterCallbackInterceptor implements Interceptor {
    private final Consumer<MethodInvocation> callback;
    private final Interceptor existing;

    AfterCallbackInterceptor(Consumer<MethodInvocation> callback, Interceptor existing) {
      this.callback = callback;
      this.existing = existing;
    }

    @Override
    public void before(MethodInvocation invocation) {
      if (existing != null) existing.before(invocation);
    }

    @Override
    public void after(MethodInvocation invocation) {
      callback.accept(invocation);
    }

    @Override
    public void onException(MethodInvocation invocation) {
      if (existing != null) existing.onException(invocation);
    }
  }

  /** Interceptor that delegates onException() to a callback. */
  static class OnExceptionCallbackInterceptor implements Interceptor {
    private final Consumer<MethodInvocation> callback;
    private final Interceptor existing;

    OnExceptionCallbackInterceptor(Consumer<MethodInvocation> callback, Interceptor existing) {
      this.callback = callback;
      this.existing = existing;
    }

    @Override
    public void before(MethodInvocation invocation) {
      if (existing != null) existing.before(invocation);
    }

    @Override
    public void after(MethodInvocation invocation) {
      if (existing != null) existing.after(invocation);
    }

    @Override
    public void onException(MethodInvocation invocation) {
      callback.accept(invocation);
    }

    @Override
    public void afterFinally(MethodInvocation invocation) {
      if (existing != null) existing.afterFinally(invocation);
    }
  }

  /** Interceptor that delegates afterFinally() to a callback. */
  static class FinallyCallbackInterceptor implements Interceptor {
    private final Consumer<MethodInvocation> callback;
    private final Interceptor existing;

    FinallyCallbackInterceptor(Consumer<MethodInvocation> callback, Interceptor existing) {
      this.callback = callback;
      this.existing = existing;
    }

    @Override
    public void before(MethodInvocation invocation) {
      if (existing != null) existing.before(invocation);
    }

    @Override
    public void after(MethodInvocation invocation) {
      if (existing != null) existing.after(invocation);
    }

    @Override
    public void onException(MethodInvocation invocation) {
      if (existing != null) existing.onException(invocation);
    }

    @Override
    public void afterFinally(MethodInvocation invocation) {
      callback.accept(invocation);
    }
  }
}
