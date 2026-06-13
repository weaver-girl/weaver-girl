package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Evicts cache entries when the intercepted method is called.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserCacheEviction {
 *     &#64;CacheEvict(value = "updateUser", keyPrefix = "user", allEntries = false)
 *     public void evictUser(MethodInvocation inv) {
 *         // cache eviction is handled automatically
 *     }
 * }
 * </pre>
 *
 * @see CacheResult
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheEvict {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Cache key prefix to evict.
     */
    String keyPrefix() default "";

    /**
     * Whether to evict all entries with the given prefix.
     */
    boolean allEntries() default false;

    /**
     * Whether to evict before or after the method executes.
     */
    boolean beforeInvocation() default false;
}
