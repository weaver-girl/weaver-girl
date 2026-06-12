package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Automatically retries the target method when it throws an exception.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <p>The framework will re-invoke the original method up to the specified
 * number of retries before giving up and calling the {@link OnException}
 * handlers (if any).</p>
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.PaymentService")
 * public class RetryInterceptor {
 *     &#64;RetryOnException(value = "processPayment", maxRetries = 3, delayMs = 100)
 *     public void retryPayment(MethodInvocation inv) {
 *         log.info("Retrying payment, attempt will be made automatically");
 *     }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @see OnException
 * @since 1.4.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetryOnException {

    /**
     * Name of the target method to intercept.
     *
     * @return the target method name
     */
    String value();

    /**
     * Maximum number of retry attempts.
     *
     * @return max retries (default 3)
     */
    int maxRetries() default 3;

    /**
     * Delay in milliseconds between retry attempts.
     *
     * @return delay in ms (default 0, no delay)
     */
    long delayMs() default 0;

    /**
     * Exception types that should trigger a retry.
     * When empty (default), all exceptions trigger a retry.
     *
     * @return exception types to retry on
     */
    Class<? extends Throwable>[] retryFor() default {};
}
