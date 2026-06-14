package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the elapsed execution time (in nanoseconds) into the advice method parameter. Only valid
 * in {@link After}, {@link AfterReturning}, and {@link OnException} advice methods.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.SlowService")
 * public class SlowServiceMonitor {
 *     &#64;After("compute")
 *     public void afterCompute(MethodInvocation inv, &#64;Elapsed long nanos) {
 *         log.info("compute took {}ms", nanos / 1_000_000);
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Elapsed {}
