package com.github.cc11001100.weavergirl.api.circuit;

import java.util.Collections;
import java.util.List;

/**
 * Process-wide access to the engine's circuit-breaker snapshots.
 *
 * <p>Mirrors {@link com.github.cc11001100.weavergirl.api.status.AgentStatusHolder}: the engine
 * installs its provider once at startup and readers in modules that must not depend on the engine
 * (the Prometheus exporter, for one) call {@link #snapshots()} and get an empty list until that
 * happens.
 *
 * <p>The provider is invoked at scrape time and must be cheap: the number of breakers is bounded by
 * the number of registered interceptor definitions.
 *
 * @since 1.0.0
 */
public final class CircuitBreakerRegistry {

  /** Supplies the current breaker snapshots. */
  @FunctionalInterface
  public interface Provider {
    List<? extends CircuitBreakerView> snapshots();
  }

  private static volatile Provider provider;

  private CircuitBreakerRegistry() {}

  /** Install the live provider. Passing {@code null} clears it. */
  public static void setProvider(Provider newProvider) {
    provider = newProvider;
  }

  /**
   * The current breaker snapshots, or an empty list when no engine is running.
   *
   * @return one entry per interceptor the breaker has observed, never null
   */
  public static List<? extends CircuitBreakerView> snapshots() {
    Provider current = provider;
    if (current == null) {
      return Collections.emptyList();
    }
    try {
      List<? extends CircuitBreakerView> snapshots = current.snapshots();
      return snapshots == null ? Collections.<CircuitBreakerView>emptyList() : snapshots;
    } catch (Throwable e) {
      // A broken provider must not break a metrics scrape.
      return Collections.emptyList();
    }
  }
}
