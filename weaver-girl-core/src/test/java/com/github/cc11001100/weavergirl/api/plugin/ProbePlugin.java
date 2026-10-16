package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * A plugin that lives in this package only so the core tests can reach the protected field and
 * catch builders. The builders stay protected because they are part of the plugin author's API,
 * not a general-purpose one, and a subclass is exactly how a real plugin uses them.
 */
public class ProbePlugin extends AbstractPlugin {

  @Override
  public String name() {
    return "probe";
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {}

  /** A field read, with the callback type named here so callers outside this package can pass one. */
  public interface Read {
    void onGet(FieldInvocation invocation, Object value);
  }

  /** A field write. */
  public interface Write {
    void onSet(FieldInvocation invocation, Object target, Object value);
  }

  /** A catch block. */
  public interface Caught {
    void onCatch(CatchInvocation invocation);
  }

  public InterceptorDefinition fieldRead(String className, String fieldName, final Read onGet) {
    return interceptField(className, fieldName)
        .onGet((invocation, value) -> onGet.onGet(invocation, value))
        .build();
  }

  public InterceptorDefinition fieldWrite(String className, String fieldName, final Write onSet) {
    return interceptField(className, fieldName)
        .onSet((invocation, target, value) -> onSet.onSet(invocation, target, value))
        .build();
  }

  public InterceptorDefinition catching(
      String className, String exceptionTypeName, final Caught onCatch) {
    return interceptCatch(className, exceptionTypeName)
        .onCatch(invocation -> onCatch.onCatch(invocation))
        .build();
  }
}
