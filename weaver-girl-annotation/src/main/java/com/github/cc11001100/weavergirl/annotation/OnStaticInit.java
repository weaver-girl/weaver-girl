package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Intercepts the static initializer (&lt;clinit&gt;) of the target class.
 * Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.HeavyConfig")
 * public class StaticInitMonitor {
 *     &#64;OnStaticInit
 *     public void onClassLoad(MethodInvocation inv) {
 *         log.info("HeavyConfig static initializer executed");
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnStaticInit {
}
