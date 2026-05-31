package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an around-advice for a specific target method.
 * The advice method receives the MethodInvocation and can call
 * before/after/exception logic in a single method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Around {

    /**
     * Name of the target method to intercept.
     */
    String value();
}
