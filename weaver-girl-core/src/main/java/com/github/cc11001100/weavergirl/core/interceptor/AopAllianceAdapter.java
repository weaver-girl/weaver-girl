package com.github.cc11001100.weavergirl.core.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;

/**
 * Adapts an AOP Alliance {@link org.aopalliance.intercept.MethodInterceptor} to a weaver-girl {@link
 * Interceptor}.
 *
 * <p>Usage:</p>
 *
 * <pre>
 *   org.aopalliance.intercept.MethodInterceptor aop =
 *       new org.aopalliance.intercept.MethodInterceptor() {
 *         &#64;Override
 *         public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
 *           return invocation.proceed();
 *         }
 *       };
 *
 *   Interceptor adapted = new AopAllianceAdapter(aop);
 * </pre>
 *
 * @since 2.0.0
 */
public class AopAllianceAdapter implements Interceptor {

  private final org.aopalliance.intercept.MethodInterceptor delegate;

  public AopAllianceAdapter(org.aopalliance.intercept.MethodInterceptor delegate) {
    this.delegate = delegate;
  }

  @Override
  public void before(MethodInvocation invocation) {
    invokeAround(invocation);
  }

  @Override
  public void after(MethodInvocation invocation) {
    // no-op: AOP Alliance semantics are fully expressed via around()
  }

  @Override
  public void onException(MethodInvocation invocation) {
    // no-op: exceptions propagate out of invoke() to the engine
  }

  @Override
  public void around(MethodInvocation invocation) {
    invokeAround(invocation);
  }

  @Override
  public boolean hasAround() {
    return true;
  }

  private void invokeAround(MethodInvocation invocation) {
    if (delegate == null || invocation == null) {
      return;
    }
    try {
      AopInvocation aopInvocation = new AopInvocation(invocation);
      Object result = delegate.invoke(aopInvocation);
      if (result != null) {
        invocation.setReturnValue(result);
      }
    } catch (Throwable throwable) {
      invocation.setThrowable(throwable);
      throw new RuntimeException(throwable);
    }
  }

  private static class AopInvocation implements org.aopalliance.intercept.MethodInvocation {
    private final MethodInvocation invocation;
    private final Method method;

    AopInvocation(MethodInvocation invocation) {
      this.invocation = invocation;
      this.method = invocation.getMethod();
    }

    @Override
    public Object proceed() throws Throwable {
      Object result = invokeTarget();
      if (result != null) {
        invocation.setReturnValue(result);
      }
      return result;
    }

    @Override
    public Object[] getArguments() {
      return invocation.getArguments();
    }

    @Override
    public Method getMethod() {
      return method;
    }

    @Override
    public AccessibleObject getStaticPart() {
      return method;
    }

    @Override
    public Object getThis() {
      return invocation.getTarget();
    }

    private Object invokeTarget() throws Throwable {
      Object target = invocation.getTarget();
      if (target == null || method == null) {
        return null;
      }
      Object[] args = invocation.getArguments();
      return method.invoke(target, args);
    }
  }
}
