package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an after-advice for a specific target method.
 * Called after the target method returns successfully.
 *
 * <p>The annotated method must accept a single MethodInvocation parameter.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface After {

    /**
     * Name of the target method to intercept.
     */
    String value();
}
