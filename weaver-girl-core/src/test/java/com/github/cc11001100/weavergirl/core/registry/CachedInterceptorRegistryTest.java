package com.github.cc11001100.weavergirl.core.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import java.util.List;
import org.junit.jupiter.api.*;

/** Tests for performance optimizations (P54). */
class CachedInterceptorRegistryTest {

  private DefaultInterceptorRegistry registry;
  private CachedInterceptorRegistry cached;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    cached = new CachedInterceptorRegistry(registry);
  }

  // ===== CachedInterceptorRegistry =====

  @Test
  void getInterceptorsForClass_returnsCorrectResults() {
    registry.register(
        new InterceptorDefinition(
            "test-def",
            new Pointcut(
                ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("execute")),
            new Interceptor() {},
            0));

    List<InterceptorDefinition> result = cached.getInterceptorsForClass("com.example.Service");
    assertEquals(1, result.size());
    assertEquals("test-def", result.get(0).getName());
  }

  @Test
  void cacheHitsOnSecondCall() {
    registry.register(
        new InterceptorDefinition(
            "cached-test",
            new Pointcut(ClassMatcher.byName("com.example.Cached"), MethodMatcher.any()),
            new Interceptor() {},
            0));

    // First call: miss
    cached.getInterceptorsForClass("com.example.Cached");
    assertEquals(0, cached.getHits());
    assertEquals(1, cached.getMisses());

    // Second call: hit
    cached.getInterceptorsForClass("com.example.Cached");
    assertEquals(1, cached.getHits());
  }

  @Test
  void hitRate_calculatesCorrectly() {
    registry.register(
        new InterceptorDefinition(
            "rate-test",
            new Pointcut(ClassMatcher.byName("com.example.Rate"), MethodMatcher.any()),
            new Interceptor() {},
            0));

    cached.getInterceptorsForClass("com.example.Rate"); // miss
    cached.getInterceptorsForClass("com.example.Rate"); // hit
    cached.getInterceptorsForClass("com.example.Rate"); // hit

    double rate = cached.getHitRate();
    assertTrue(rate > 0.5, "Hit rate should be >50%, got " + rate);
  }

  @Test
  void invalidate_clearsCache() {
    registry.register(
        new InterceptorDefinition(
            "inv-test",
            new Pointcut(ClassMatcher.byName("com.example.Inv"), MethodMatcher.any()),
            new Interceptor() {},
            0));

    cached.getInterceptorsForClass("com.example.Inv");
    assertEquals(1, cached.size());

    cached.invalidate();
    assertEquals(0, cached.size());
  }

  @Test
  void cache_boundedByMaxSize() {
    CachedInterceptorRegistry smallCache = new CachedInterceptorRegistry(registry, 10);

    for (int i = 0; i < 20; i++) {
      registry.register(
          new InterceptorDefinition(
              "bound-" + i,
              new Pointcut(ClassMatcher.byName("com.example.Clazz" + i), MethodMatcher.any()),
              new Interceptor() {},
              0));
    }

    for (int i = 0; i < 20; i++) {
      smallCache.getInterceptorsForClass("com.example.Clazz" + i);
    }

    assertTrue(smallCache.size() <= 10, "Cache should be bounded");
  }

  @Test
  void getInterceptorsForClass_emptyResult() {
    List<InterceptorDefinition> result = cached.getInterceptorsForClass("com.example.Nonexistent");
    assertTrue(result.isEmpty());
  }
}
