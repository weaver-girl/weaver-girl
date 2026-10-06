package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records the elapsed time of the intercepted method and logs a warning when it exceeds the
 * configured threshold. Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <p>Lighter than {@link Timed} (no metric registry involved): purely a log-based slow-call
 * detector for quick production triage.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.ReportService")
 * public class SlowReportWatch {
 *     &#64;WarnIfSlow(value = "buildReport", thresholdMs = 2000)
 *     public void watchBuild(MethodInvocation inv) {
 *         // builds slower than 2s leave a WARN with the elapsed time
 *     }
 * }
 * </pre>
 *
 * @see Timed
 * @see Logged
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WarnIfSlow {

  /** Name of the target method to intercept. */
  String value();

  /** Threshold in milliseconds; slower executions trigger a WARN log. */
  long thresholdMs() default 1000;
}
