package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an exception-advice for a specific target method.
 * Called when the target method throws an exception.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 * The annotated method must accept a single {@code MethodInvocation} parameter.
 * The thrown exception is available via {@code invocation.getThrowable()}.</p>
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.PaymentService")
 * public class PaymentErrorHandler {
 *     &#64;OnException("processPayment")
 *     public void handlePaymentError(MethodInvocation inv) {
 *         Throwable error = inv.getThrowable();
 *         log.error("Payment failed: {}", error.getMessage());
 *         // Optionally suppress the exception:
 *         // inv.suppressException();
 *         // inv.setReturnValue(fallbackValue);
 *     }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @see Before
 * @see After
 * @see Around
 * @since 1.3.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnException {

    /**
     * Name of the target method to intercept.
     *
     * @return the target method name
     */
    String value();
}
