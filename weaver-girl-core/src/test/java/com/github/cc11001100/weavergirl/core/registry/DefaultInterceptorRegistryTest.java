// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistryTest.java
package com.github.cc11001100.weavergirl.core.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultInterceptorRegistryTest {

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
  }

  @Test
  void register_andLookupByClassName_returnsMatchingDefinition() {
    InterceptorDefinition def = createDefinition("test", "com.example.TargetService", "doWork");
    registry.register(def);

    List<InterceptorDefinition> result =
        registry.getInterceptorsForClass("com.example.TargetService");
    assertEquals(1, result.size());
    assertEquals("test", result.get(0).getName());
  }

  @Test
  void register_nullDefinition_ignored() {
    registry.register(null);
    assertEquals(0, registry.getAllDefinitions().size());
  }

  @Test
  void getInterceptorsForClass_noMatch_returnsEmptyList() {
    InterceptorDefinition def = createDefinition("test", "com.example.TargetService", "doWork");
    registry.register(def);

    List<InterceptorDefinition> result =
        registry.getInterceptorsForClass("com.example.OtherService");
    assertTrue(result.isEmpty());
  }

  @Test
  void register_multipleDefinitions_sortedByPriority() {
    InterceptorDefinition low = createDefinition("low", "com.example.Svc", "run", 10);
    InterceptorDefinition high = createDefinition("high", "com.example.Svc", "run", 1);
    registry.register(low);
    registry.register(high);

    List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
    assertEquals(2, result.size());
    assertEquals("high", result.get(0).getName());
    assertEquals("low", result.get(1).getName());
  }

  @Test
  void clear_removesAllDefinitions() {
    registry.register(createDefinition("test", "com.example.Svc", "run"));
    registry.clear();
    assertEquals(0, registry.getAllDefinitions().size());
  }

  private InterceptorDefinition createDefinition(String name, String className, String methodName) {
    return createDefinition(name, className, methodName, 0);
  }

  private InterceptorDefinition createDefinition(
      String name, String className, String methodName, int priority) {
    Pointcut pointcut =
        new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName(methodName));
    Interceptor interceptor = new Interceptor() {};
    return new InterceptorDefinition(name, pointcut, interceptor, priority);
  }
}
