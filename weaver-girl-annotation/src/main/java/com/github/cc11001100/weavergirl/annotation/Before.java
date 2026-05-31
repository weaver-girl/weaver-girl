package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a before-advice for a specific target method.
 * Must be used within a class annotated with @WeaveClass.
 *
 * <p>The annotated method must accept a single MethodInvocation parameter.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Before {

    /**
     * Name of the target method to intercept.
     */
    String value();
}
