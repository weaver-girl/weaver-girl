package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

/** Test interceptor used by YamlConfigLoaderTest for validation testing. */
public class TestInterceptor implements Interceptor {

  @Override
  public void before(MethodInvocation invocation) {}

  @Override
  public void after(MethodInvocation invocation) {}
}
