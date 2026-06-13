package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Audits method invocations by logging who called what and when.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.FinanceService")
 * public class FinanceAudit {
 *     &#64;Audited(value = "transfer", action = "TRANSFER", includeArgs = true)
 *     public void auditTransfer(MethodInvocation inv) {
 *         // audit log is automatically created
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Action name for the audit log.
     */
    String action() default "";

    /**
     * Whether to include method arguments in the audit log.
     */
    boolean includeArgs() default false;

    /**
     * Whether to include the return value in the audit log.
     */
    boolean includeResult() default false;
}
