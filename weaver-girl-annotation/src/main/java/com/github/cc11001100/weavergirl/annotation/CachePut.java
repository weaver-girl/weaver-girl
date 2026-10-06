package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Writes the return value of the intercepted method into the shared cache, overwriting any
 * existing entry for the computed key. Applied at the method level within a {@link WeaveClass}
 * interceptor.
 *
 * <p>Complements {@link CacheResult} (read path) and {@link CacheEvict} (invalidation): use {@code
 * @CachePut} on write/update methods so subsequent {@code @CacheResult} reads observe fresh data
 * without an explicit eviction round-trip.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserCacheWriter {
 *     &#64;CachePut(value = "updateUser", ttlMs = 60000)
 *     public void refreshUser(MethodInvocation inv) {
 *         // the fresh return value is automatically cached
 *     }
 * }
 * </pre>
 *
 * @see CacheResult
 * @see CacheEvict
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CachePut {

  /** Name of the target method to intercept. */
  String value();

  /** Time-to-live in milliseconds. 0 means no expiration. */
  long ttlMs() default 0;

  /** Cache key prefix. When empty, the method name is used. */
  String keyPrefix() default "";

  /** Indices of arguments to include in the cache key. When empty, all arguments are used. */
  int[] keyArgIndices() default {};
}
