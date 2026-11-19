package com.github.cc11001100.weavergirl.api.circuit;

/**
 * Read-only view of one interceptor's circuit-breaker state, so a metrics bridge that lives outside
 * the engine can render it without depending on the engine's internal breaker type.
 *
 * @since 1.0.0
 */
public interface CircuitBreakerView {

  /** The interceptor definition name this breaker guards. */
  String getInterceptorName();

  /** Whether the breaker is currently OPEN, i.e. callbacks for this interceptor are skipped. */
  boolean isOpen();

  /** Consecutive failures recorded since the last success. */
  int getConsecutiveFailures();

  /** Consecutive slow calls recorded since the last fast call. */
  int getConsecutiveSlow();

  /** How many times the breaker has tripped OPEN since the process started. */
  long getTimesTripped();
}
