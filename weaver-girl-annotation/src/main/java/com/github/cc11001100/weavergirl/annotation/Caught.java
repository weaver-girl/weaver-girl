package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the exception caught by the intercepted catch block into an {@link OnCatch} advice
 * method parameter.
 *
 * <p>A parameter carrying none of the injection annotations receives the {@link
 * com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation} itself, so {@code @Caught} is
 * only needed when the handler wants the raw exception without the invocation context.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.RiskyService")
 * public class IoErrorHandler {
 *     &#64;OnCatch("java.io.IOException")
 *     public void onIoError(&#64;Caught Throwable error) {
 *         log.warn("io failed: {}", error.getMessage());
 *     }
 * }
 * </pre>
 *
 * @see OnCatch
 * @since 1.10.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Caught {}
