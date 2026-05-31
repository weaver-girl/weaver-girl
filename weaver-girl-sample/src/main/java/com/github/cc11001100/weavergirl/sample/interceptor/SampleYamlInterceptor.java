// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/interceptor/SampleYamlInterceptor.java
package com.github.cc11001100.weavergirl.sample.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

/**
 * Sample Interceptor implementation referenced by YAML config.
 */
public class SampleYamlInterceptor implements Interceptor {

    @Override
    public void before(MethodInvocation invocation) {
        System.out.println("[YAML] Before " + invocation.getMethodName()
                + " args=" + java.util.Arrays.toString(invocation.getArguments()));
    }

    @Override
    public void after(MethodInvocation invocation) {
        System.out.println("[YAML] After " + invocation.getMethodName()
                + " returned=" + invocation.getReturnValue());
    }
}
