package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The builder interceptors forward the phases they do not own to the interceptor they wrap, and do
 * nothing when there is none. Each one is driven through every phase, with a delegate and without.
 */
class BuilderInterceptorsTest {

  @Test
  void eachCallbackRunsItsOwnPhaseAndForwardsTheRest() {
    MethodInvocation invocation = new MethodInvocation(getClass(), "run", null, null);
    AtomicInteger callbacks = new AtomicInteger();
    Consumer<MethodInvocation> callback = inv -> callbacks.incrementAndGet();
    Interceptor existing =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            callbacks.addAndGet(10);
          }

          @Override
          public void after(MethodInvocation inv) {
            callbacks.addAndGet(100);
          }

          @Override
          public void onException(MethodInvocation inv) {
            callbacks.addAndGet(1000);
          }

          @Override
          public void afterFinally(MethodInvocation inv) {
            callbacks.addAndGet(10000);
          }
        };

    BuilderInterceptors.BeforeCallbackInterceptor before =
        new BuilderInterceptors.BeforeCallbackInterceptor(callback, existing);
    before.before(invocation);
    before.after(invocation);
    before.onException(invocation);

    BuilderInterceptors.AfterCallbackInterceptor after =
        new BuilderInterceptors.AfterCallbackInterceptor(callback, existing);
    after.before(invocation);
    after.after(invocation);
    after.onException(invocation);

    BuilderInterceptors.OnExceptionCallbackInterceptor onException =
        new BuilderInterceptors.OnExceptionCallbackInterceptor(callback, existing);
    onException.before(invocation);
    onException.after(invocation);
    onException.onException(invocation);
    onException.afterFinally(invocation);

    BuilderInterceptors.FinallyCallbackInterceptor afterFinally =
        new BuilderInterceptors.FinallyCallbackInterceptor(callback, existing);
    afterFinally.before(invocation);
    afterFinally.after(invocation);
    afterFinally.onException(invocation);
    afterFinally.afterFinally(invocation);

    // Four callbacks fire their own phase, and the wrapped interceptor sees every phase it owns.
    assertEquals(4 + 10 * 3 + 100 * 3 + 1000 * 3 + 10000, callbacks.get());
  }

  @Test
  void nothingIsForwardedWhenThereIsNoExistingInterceptor() {
    MethodInvocation invocation = new MethodInvocation(getClass(), "run", null, null);
    AtomicInteger callbacks = new AtomicInteger();
    Consumer<MethodInvocation> callback = inv -> callbacks.incrementAndGet();

    new BuilderInterceptors.BeforeCallbackInterceptor(callback, null).after(invocation);
    new BuilderInterceptors.BeforeCallbackInterceptor(callback, null).onException(invocation);
    new BuilderInterceptors.AfterCallbackInterceptor(callback, null).before(invocation);
    new BuilderInterceptors.AfterCallbackInterceptor(callback, null).onException(invocation);
    new BuilderInterceptors.OnExceptionCallbackInterceptor(callback, null).before(invocation);
    new BuilderInterceptors.OnExceptionCallbackInterceptor(callback, null).after(invocation);
    new BuilderInterceptors.OnExceptionCallbackInterceptor(callback, null).afterFinally(invocation);
    new BuilderInterceptors.FinallyCallbackInterceptor(callback, null).before(invocation);
    new BuilderInterceptors.FinallyCallbackInterceptor(callback, null).after(invocation);
    new BuilderInterceptors.FinallyCallbackInterceptor(callback, null).onException(invocation);

    assertEquals(0, callbacks.get(), "a phase owned by nobody still ran the callback");
  }
}
