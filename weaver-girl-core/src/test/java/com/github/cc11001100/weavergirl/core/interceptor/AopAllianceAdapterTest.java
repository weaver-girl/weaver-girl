package com.github.cc11001100.weavergirl.core.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;

class AopAllianceAdapterTest {

  static class Target {
    public String greet(String name) {
      return "hello:" + name;
    }
  }

  @Test
  void adaptsAroundAdviceAndPreservesReturnValue() throws Throwable {
    MethodInterceptor delegate = invocation -> {
      Object[] args = invocation.getArguments();
      return "wrapped:" + args[0];
    };

    AopAllianceAdapter adapter = new AopAllianceAdapter(delegate);
    Method method = Target.class.getMethod("greet", String.class);
    MethodInvocation invocation = new MethodInvocation(Target.class, "greet", method, new Target(), new Object[]{"world"});

    adapter.around(invocation);

    assertEquals("wrapped:world", invocation.getReturnValue());
  }

  @Test
  void propagatesDelegateException() throws Throwable {
    MethodInterceptor delegate = invocation -> {
      throw new IllegalStateException("boom");
    };

    AopAllianceAdapter adapter = new AopAllianceAdapter(delegate);
    Method method = Target.class.getMethod("greet", String.class);
    MethodInvocation invocation = new MethodInvocation(Target.class, "greet", method, new Target(), new Object[]{"world"});

    RuntimeException thrown = assertThrows(RuntimeException.class, () -> adapter.around(invocation));
    assertTrue(thrown.getCause() instanceof IllegalStateException);
    assertEquals("boom", thrown.getCause().getMessage());
    assertTrue(invocation.hasException());
  }

  @Test
  void delegatesMethodMetadata() throws Throwable {
    Method method = Target.class.getMethod("greet", String.class);
    MethodInvocation invocation = new MethodInvocation(Target.class, "greet", method, new Target(), new Object[]{"world"});

    MethodInterceptor delegate = aopInvocation -> {
      assertEquals(Target.class, aopInvocation.getThis().getClass());
      assertEquals(method, aopInvocation.getMethod());
      assertArrayEquals(new Object[]{"world"}, aopInvocation.getArguments());
      return "ok";
    };

    AopAllianceAdapter adapter = new AopAllianceAdapter(delegate);
    adapter.around(invocation);
    assertEquals("ok", invocation.getReturnValue());
  }
}
