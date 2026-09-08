package com.github.cc11001100.weavergirl.api.annotation;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for custom annotation handlers.
 *
 * <p>Maintains a mapping from annotation class names to {@link AnnotationHandler} instances. When
 * {@link #handleAnnotations} is called, each annotation present on the target element is looked up
 * and the corresponding handler is invoked.
 *
 * <h3>Thread safety</h3>
 *
 * <p>All operations are thread-safe. Internal state is backed by a {@link ConcurrentHashMap}.
 *
 * <h3>Singleton access</h3>
 *
 * <p>Use {@link #getInstance()} to obtain the global registry instance.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();
 *
 * // Register a handler for a custom annotation
 * registry.register(new MyCustomAnnotationHandler());
 *
 * // During class scanning, invoke handlers for discovered annotations
 * registry.handleAnnotations(serviceClass, serviceInstance, interceptorRegistry);
 *
 * // Remove when no longer needed
 * registry.unregister("com.example.CustomAnnotation");
 * </pre>
 *
 * @see AnnotationHandler
 * @since 1.1.0
 */
public final class AnnotationHandlerRegistry {

  private static final AnnotationHandlerRegistry INSTANCE = new AnnotationHandlerRegistry();

  private final ConcurrentHashMap<String, AnnotationHandler> handlers = new ConcurrentHashMap<>();

  private AnnotationHandlerRegistry() {}

  /**
   * Returns the singleton registry instance.
   *
   * @return the global AnnotationHandlerRegistry
   */
  public static AnnotationHandlerRegistry getInstance() {
    return INSTANCE;
  }

  /**
   * Register an annotation handler.
   *
   * <p>If a handler is already registered for the same annotation type (as determined by {@link
   * AnnotationHandler#supportedAnnotation()}), it will be replaced.
   *
   * @param handler the handler to register; must not be null
   * @throws IllegalArgumentException if handler is null
   */
  public void register(AnnotationHandler handler) {
    if (handler == null) {
      throw new IllegalArgumentException("AnnotationHandler must not be null");
    }
    String key = handler.supportedAnnotation().getName();
    handlers.put(key, handler);
  }

  /**
   * Unregister a handler by annotation class name.
   *
   * @param annotationClassName the fully-qualified class name of the annotation whose handler
   *     should be removed
   * @return true if a handler was removed, false if no handler was registered for the given
   *     annotation class name
   */
  public boolean unregister(String annotationClassName) {
    if (annotationClassName == null) {
      return false;
    }
    return handlers.remove(annotationClassName) != null;
  }

  /**
   * Check whether a handler is registered for the given annotation type.
   *
   * @param annotationClass the annotation class to check
   * @return true if a handler is registered for this annotation type
   */
  public boolean hasHandler(Class<? extends Annotation> annotationClass) {
    if (annotationClass == null) {
      return false;
    }
    return handlers.containsKey(annotationClass.getName());
  }

  /**
   * Returns the set of registered annotation class names.
   *
   * @return an unmodifiable set of fully-qualified annotation class names that have registered
   *     handlers
   */
  public Set<String> getRegisteredAnnotations() {
    return Collections.unmodifiableSet(handlers.keySet());
  }

  /**
   * Get a handler by annotation class name.
   *
   * @param annotationClassName the fully-qualified class name of the annotation
   * @return the handler registered for the given annotation, or null if none exists
   */
  public AnnotationHandler getHandler(String annotationClassName) {
    if (annotationClassName == null) {
      return null;
    }
    return handlers.get(annotationClassName);
  }

  /** Remove all registered handlers. */
  public void clear() {
    handlers.clear();
  }

  /**
   * Inspect the annotations present on the given element and invoke any matching registered
   * handlers.
   *
   * <p>For each annotation present on the element, if a handler has been registered for that
   * annotation type, the handler's {@link AnnotationHandler#handle} method is called with the
   * element, instance, and registry.
   *
   * @param element the annotated element to process (class, method, field, etc.)
   * @param instance the object instance associated with the element, may be null
   * @param registry the interceptor registry to pass to handlers
   */
  public void handleAnnotations(
      AnnotatedElement element, Object instance, InterceptorRegistry registry) {
    if (element == null || registry == null) {
      return;
    }
    Annotation[] annotations = element.getAnnotations();
    for (int i = 0; i < annotations.length; i++) {
      String annotationName = annotations[i].annotationType().getName();
      AnnotationHandler handler = handlers.get(annotationName);
      if (handler != null) {
        handler.handle(element, instance, registry);
      }
    }
  }
}
