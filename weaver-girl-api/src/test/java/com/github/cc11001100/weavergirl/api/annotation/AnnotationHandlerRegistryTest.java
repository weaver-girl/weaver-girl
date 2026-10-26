package com.github.cc11001100.weavergirl.api.annotation;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.AnnotatedElement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The handler registry is a process-wide singleton, so every test clears it. */
class AnnotationHandlerRegistryTest {

  @AfterEach
  void reset() {
    AnnotationHandlerRegistry.getInstance().clear();
  }

  @Test
  void registerReplacesAndUnregisterRemoves() {
    AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();
    AnnotationHandler first = handler(Deprecated.class);
    AnnotationHandler second = handler(Deprecated.class);

    registry.register(first);
    registry.register(second);

    assertSame(second, registry.getHandler(Deprecated.class.getName()));
    assertTrue(registry.hasHandler(Deprecated.class));
    assertTrue(registry.getRegisteredAnnotations().contains(Deprecated.class.getName()));

    assertTrue(registry.unregister(Deprecated.class.getName()));
    assertFalse(registry.hasHandler(Deprecated.class));
    assertNull(registry.getHandler(Deprecated.class.getName()));
  }

  @Test
  void nullInputsAreRejectedOrIgnored() {
    AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();

    assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    assertFalse(registry.unregister(null));
    assertFalse(registry.hasHandler(null));
    assertNull(registry.getHandler(null));
  }

  @Test
  void handleAnnotationsInvokesOnlyTheMatchingHandler() {
    AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();
    AtomicInteger calls = new AtomicInteger();
    registry.register(
        new AnnotationHandler() {
          @Override
          public Class<? extends Annotation> supportedAnnotation() {
            return Marked.class;
          }

          @Override
          public void handle(
              AnnotatedElement element, Object instance, InterceptorRegistry interceptorRegistry) {
            calls.incrementAndGet();
          }
        });

    registry.handleAnnotations(MarkedType.class, new Object(), interceptorRegistry());
    registry.handleAnnotations(String.class, null, interceptorRegistry());

    assertEquals(1, calls.get());
  }

  @Test
  void handleAnnotationsDoesNothingWithoutAnElementOrRegistry() {
    AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();
    registry.register(handler(Marked.class));

    assertDoesNotThrow(() -> registry.handleAnnotations(null, null, interceptorRegistry()));
    assertDoesNotThrow(() -> registry.handleAnnotations(MarkedType.class, null, null));
  }

  private static AnnotationHandler handler(Class<? extends Annotation> type) {
    return new AnnotationHandler() {
      @Override
      public Class<? extends Annotation> supportedAnnotation() {
        return type;
      }

      @Override
      public void handle(
          AnnotatedElement element, Object instance, InterceptorRegistry interceptorRegistry) {}
    };
  }

  private static InterceptorRegistry interceptorRegistry() {
    return new InterceptorRegistry() {
      @Override
      public void register(
          com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition definition) {}

      @Override
      public java.util.List<com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition>
          getInterceptorsForClass(String className) {
        return java.util.Collections.emptyList();
      }

      @Override
      public java.util.List<com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition>
          getAllDefinitions() {
        return java.util.Collections.emptyList();
      }

      @Override
      public boolean unregister(String name) {
        return false;
      }
    };
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface Marked {}

  @Marked
  static class MarkedType {}
}
