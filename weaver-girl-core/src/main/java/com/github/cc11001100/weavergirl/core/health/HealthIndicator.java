package com.github.cc11001100.weavergirl.core.health;

/** Functional interface for a component health check. */
@FunctionalInterface
public interface HealthIndicator {
  /** Evaluate the health of a component and return its status. */
  HealthStatus check();
}
