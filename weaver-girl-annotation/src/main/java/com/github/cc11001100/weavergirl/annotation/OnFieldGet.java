package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Intercepts field read access (getfield) on the target class. Must be used within a class
 * annotated with {@link WeaveClass}.
 *
 * <p>The advice method receives a {@link
 * com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation}. Calling {@code
 * setReturnValue(...)} on it replaces the value the read returns to the caller, so a field read
 * can be masked or substituted without touching the stored value.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.ConfigService")
 * public class ConfigFieldMonitor {
 *     &#64;OnFieldGet("secret")
 *     public void maskSecret(
 *         com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation inv) {
 *         inv.setReturnValue("***");
 *     }
 * }
 * </pre>
 *
 * @see OnFieldSet
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnFieldGet {

  /** Name of the field to intercept. */
  String value();
}
