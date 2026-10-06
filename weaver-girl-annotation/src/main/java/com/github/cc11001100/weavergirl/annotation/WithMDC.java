package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a set of key-value pairs into the current thread's {@code ThreadContext} for the
 * duration of the intercepted method, restoring the previous value of each key afterward. Applied
 * at the method level within a {@link WeaveClass} interceptor.
 *
 * <p>This is the declarative counterpart to imperatively calling {@code ThreadContext.put(...)} in
 * a {@link Before}/{@link After} pair: it scopes extra correlation context (request ids, tenant
 * ids, user ids) to just the intercepted method. The values become visible to any interceptor or
 * application code that reads {@code ThreadContext} during the method, and — for the three bridge
 * keys {@code traceId}/{@code spanId}/{@code tenantId} — they are also automatically carried into
 * SLF4J MDC by the engine's {@code MdcInjector}.
 *
 * <p><strong>Semantics:</strong> the configured keys are written into {@code ThreadContext} before
 * the method runs; on method exit (both normal and exceptional), the previous value of each key is
 * restored. Keys absent beforehand are removed afterward.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.BillingService")
 * public class BillingContext {
 *     &#64;WithMDC(value = "charge", keys = {"billing.tenant", "billing.requestId"},
 *         argIndices = {0, 1})
 *     public void chargeWithContext(MethodInvocation inv) {
 *         // the method runs with billing.tenant/billing.requestId in context
 *     }
 * }
 * </pre>
 *
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WithMDC {

  /** Name of the target method to intercept. */
  String value();

  /** Context keys to write for the duration of the method. */
  String[] keys();

  /** Argument indices whose {@code String} values populate the corresponding {@link #keys()}. */
  int[] argIndices() default {};

  /** Optional static values for keys not backed by an argument. Empty means leave unchanged. */
  String[] staticValues() default {};
}
