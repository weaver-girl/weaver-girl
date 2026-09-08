package com.github.cc11001100.weavergirl.api.annotation;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;

/**
 * Handles a specific annotation type found during class scanning.
 *
 * <p>Implementations are registered with {@link
 * AnnotationHandlerRegistry#register(AnnotationHandler)} and will be invoked automatically when the
 * associated annotation is discovered on a class, method, or other annotated element.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * public class MonitoredHandler implements AnnotationHandler {
 *     &#64;Override
 *     public Class&lt;? extends Annotation&gt; supportedAnnotation() {
 *         return Monitored.class;
 *     }
 *
 *     &#64;Override
 *     public void handle(AnnotatedElement element, Object instance, InterceptorRegistry registry) {
 *         // Register an interceptor for the annotated element
 *         registry.register(new InterceptorDefinition(
 *             "monitored-" + element.toString(),
 *             pointcut,
 *             interceptor
 *         ));
 *     }
 * }</pre>
 *
 * @see AnnotationHandlerRegistry
 * @see InterceptorRegistry
 * @since 1.1.0
 */
public interface AnnotationHandler {

  /**
   * Returns the annotation type this handler supports.
   *
   * @return the annotation class this handler processes
   */
  Class<? extends Annotation> supportedAnnotation();

  /**
   * Handle the annotation found on the given element.
   *
   * <p>Called by {@link AnnotationHandlerRegistry#handleAnnotations} when an annotation matching
   * {@link #supportedAnnotation()} is discovered. Implementations may use the provided {@link
   * InterceptorRegistry} to register interceptors dynamically.
   *
   * @param element the annotated element (class, method, field, etc.)
   * @param instance the instance of the class containing the annotation, may be null if the element
   *     is a class-level annotation and no instance exists yet
   * @param registry the interceptor registry for registering interceptors
   */
  void handle(AnnotatedElement element, Object instance, InterceptorRegistry registry);
}
