package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caches the return value of the intercepted method.
 * Subsequent calls with the same arguments return the cached value
 * without invoking the target method.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.</p>
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserCache {
 *     &#64;CacheResult(value = "findById", ttlMs = 60000, maxSize = 1000)
 *     public void cacheUser(MethodInvocation inv) {
 *         // caching is handled automatically
 *     }
 * }
 * </pre>
 *
 * @see CacheEvict
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheResult {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Time-to-live in milliseconds. 0 means no expiration.
     */
    long ttlMs() default 0;

    /**
     * Maximum number of cached entries.
     */
    int maxSize() default 1000;

    /**
     * Cache key prefix. When empty, the method name is used.
     */
    String keyPrefix() default "";

    /**
     * Indices of arguments to include in the cache key.
     * When empty, all arguments are used.
     */
    int[] keyArgIndices() default {};
}
