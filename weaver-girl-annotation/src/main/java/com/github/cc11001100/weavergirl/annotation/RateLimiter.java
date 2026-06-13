package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Limits the rate of method invocations.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.ApiService")
 * public class ApiRateLimiter {
 *     &#64;RateLimiter(value = "callExternalApi", permitsPerSecond = 100)
 *     public void limitApiCalls(MethodInvocation inv) {
 *         // rate limiting is automatically enforced
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimiter {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Number of permits per second.
     */
    double permitsPerSecond() default 100;

    /**
     * Maximum time to wait for a permit in milliseconds.
     * 0 means no waiting (fail fast).
     */
    long acquireTimeoutMs() default 0;
}
