package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Automatically records execution time for intercepted methods.
 * Applied at the class level alongside {@link WeaveClass}.
 *
 * <p>When present, the framework records the elapsed time of each intercepted
 * method call and stores it as an attachment ({@code "timed.elapsedNanos"})
 * on the {@code MethodInvocation}. It also logs the timing at INFO level.</p>
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.SlowService")
 * &#64;Timed
 * public class SlowServiceTimer { ... }
 * </pre>
 *
 * @see WeaveClass
 * @since 1.4.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Timed {

    /**
     * Whether to log the timing at INFO level. Defaults to true.
     *
     * @return true to log timing, false to only store as attachment
     */
    boolean log() default true;

    /**
     * Custom label for log messages. When empty, the target method name is used.
     *
     * @return the custom label, or empty for default
     */
    String label() default "";
}
