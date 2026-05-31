// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/interceptor/AnnotationInterceptor.java
package com.github.cc11001100.weavergirl.sample.interceptor;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

/**
 * Sample annotation-based interceptor.
 * Demonstrates declarative hook definition using @WeaveClass + @Before/@After.
 */
@WeaveClass(target = "com.github.cc11001100.weavergirl.sample.app.TargetService")
public class AnnotationInterceptor {

    @Before("greet")
    public void beforeGreet(MethodInvocation invocation) {
        System.out.println("[ANNOTATION] Before greet: " + java.util.Arrays.toString(invocation.getArguments()));
    }

    @After("greet")
    public void afterGreet(MethodInvocation invocation) {
        System.out.println("[ANNOTATION] After greet: " + invocation.getReturnValue());
    }

    @Before("calculate")
    public void beforeCalculate(MethodInvocation invocation) {
        System.out.println("[ANNOTATION] Before calculate");
    }
}
