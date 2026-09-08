// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/interceptor/AnnotationInterceptor.java
package com.github.cc11001100.weavergirl.sample.interceptor;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

/**
 * Sample annotation-based interceptor demonstrating all available advice annotations.
 *
 * <p>Shows usage of: @WeaveClass, @Order, @Before, @After, @Around, @OnException, @AfterReturning.
 */
@WeaveClass(target = "com.github.cc11001100.weavergirl.sample.app.TargetService")
@Order(10)
public class AnnotationInterceptor {

  @Before("greet")
  public void beforeGreet(MethodInvocation invocation) {
    System.out.println(
        "[ANNOTATION] Before greet: " + java.util.Arrays.toString(invocation.getArguments()));
  }

  @After("greet")
  public void afterGreet(MethodInvocation invocation) {
    System.out.println("[ANNOTATION] After greet: " + invocation.getReturnValue());
  }

  @AfterReturning("greet")
  public void afterGreetSuccess(MethodInvocation invocation) {
    System.out.println(
        "[ANNOTATION] AfterReturning greet — result: " + invocation.getReturnValue());
  }

  @Before("calculate")
  public void beforeCalculate(MethodInvocation invocation) {
    System.out.println("[ANNOTATION] Before calculate");
  }

  @OnException("calculate")
  public void onCalculateError(MethodInvocation invocation) {
    System.err.println("[ANNOTATION] calculate failed: " + invocation.getThrowable().getMessage());
  }
}
