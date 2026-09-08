package com.github.cc11001100.weavergirl.api.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import java.util.List;

/**
 * Registry for interceptor definitions.
 *
 * <p>The API module defines this interface; the core module provides the implementation. This
 * separation allows plugin developers to code against the interface without depending on the core
 * engine.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Implementations must be thread-safe. Registration and unregistration may be called from any
 * thread, and lookups may occur concurrently with mutations.
 *
 * <h3>Registration semantics</h3>
 *
 * <p>When a definition is registered with a name that already exists in the registry, the existing
 * definition is replaced by the new one. The name is obtained via {@link
 * InterceptorDefinition#getName()}.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * &#64;Override
 * public void registerInterceptors(InterceptorRegistry registry) {
 *     registry.register(myDefinition);
 *
 *     // Later, dynamically unregister:
 *     registry.unregister("my-definition-name");
 *
 *     // Query:
 *     List&lt;InterceptorDefinition&gt; defs = registry.getInterceptorsForClass("com.example.Service");
 * }</pre>
 *
 * @see InterceptorDefinition
 * @since 1.0.0
 */
public interface InterceptorRegistry {

  /**
   * Register an interceptor definition.
   *
   * <p>If a definition with the same {@link InterceptorDefinition#getName() name} is already
   * registered, it is replaced.
   *
   * @param definition the interceptor definition to register; must not be null
   */
  void register(InterceptorDefinition definition);

  /**
   * Unregister an interceptor definition by name.
   *
   * @param name the name of the interceptor definition to remove (as returned by {@link
   *     InterceptorDefinition#getName()})
   * @return true if a definition was removed, false if no definition with that name was found
   */
  boolean unregister(String name);

  /**
   * Get all interceptor definitions whose pointcut matches the given class name.
   *
   * <p>The returned list is ordered by {@link InterceptorDefinition#getPriority() priority} (lower
   * values first).
   *
   * @param className the fully-qualified class name to look up
   * @return a list of matching interceptor definitions, possibly empty but never null
   */
  List<InterceptorDefinition> getInterceptorsForClass(String className);

  /**
   * Get all registered definitions.
   *
   * @return an unmodifiable list of all registered interceptor definitions, possibly empty but
   *     never null
   */
  List<InterceptorDefinition> getAllDefinitions();
}
