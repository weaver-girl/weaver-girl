package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for BuilderInterceptors.
 */
class BuilderInterceptorsTest {

  @Test
  void beforeCallbackInterceptor_delegatesBefore() {
    Consumer<MethodInvocation> callback = inv -> {};
    Interceptor existing = Mockito.mock(Interceptor.class);
    BuilderInterceptors.BeforeCallbackInterceptor interceptor =
        new BuilderInterceptors.BeforeCallbackInterceptor(callback, existing);

    assertNotNull(interceptor);
  }

  @Test
  void afterCallbackInterceptor_delegatesAfter() {
    Consumer<MethodInvocation> callback = inv -> {};
    Interceptor existing = Mockito.mock(Interceptor.class);
    BuilderInterceptors.AfterCallbackInterceptor interceptor =
        new BuilderInterceptors.AfterCallbackInterceptor(callback, existing);

    assertNotNull(interceptor);
  }

  @Test
  void onExceptionCallbackInterceptor_delegatesOnException() {
    Consumer<MethodInvocation> callback = inv -> {};
    Interceptor existing = Mockito.mock(Interceptor.class);
    BuilderInterceptors.OnExceptionCallbackInterceptor interceptor =
        new BuilderInterceptors.OnExceptionCallbackInterceptor(callback, existing);

    assertNotNull(interceptor);
  }

  @Test
  void finallyCallbackInterceptor_delegatesFinally() {
    Consumer<MethodInvocation> callback = inv -> {};
    Interceptor existing = Mockito.mock(Interceptor.class);
    BuilderInterceptors.FinallyCallbackInterceptor interceptor =
        new BuilderInterceptors.FinallyCallbackInterceptor(callback, existing);

    assertNotNull(interceptor);
  }
}
