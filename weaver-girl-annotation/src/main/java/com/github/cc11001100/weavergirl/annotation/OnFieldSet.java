package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Intercepts field write access (putfield) on the target class.
 * Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.ConfigService")
 * public class ConfigFieldMonitor {
 *     &#64;OnFieldSet("config")
 *     public void monitorConfigWrite(MethodInvocation inv) {
 *         log.info("Config field 'config' was modified to: {}", inv.getReturnValue());
 *     }
 * }
 * </pre>
 *
 * @see OnFieldGet
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnFieldSet {

    /**
     * Name of the field to intercept.
     */
    String value();
}
