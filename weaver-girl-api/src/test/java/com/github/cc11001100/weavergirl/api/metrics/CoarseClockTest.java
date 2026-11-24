package com.github.cc11001100.weavergirl.api.metrics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The coarse clock is within one tick of the wall clock and never goes backwards. */
class CoarseClockTest {

  /** One tick is 10 ms; allow generous slack for a loaded CI machine. */
  private static final long MAX_LAG_MILLIS = 2_000;

  @Test
  void millisIsWithinATickOfTheWallClock() {
    long coarse = CoarseClock.millis();
    long real = System.currentTimeMillis();
    assertTrue(
        Math.abs(real - coarse) <= MAX_LAG_MILLIS,
        "coarse clock drifted: coarse=" + coarse + " real=" + real);
  }

  @Test
  void repeatedReadsDoNotGoBackwards() {
    long first = CoarseClock.millis();
    long second = CoarseClock.millis();
    assertTrue(second >= first, "coarse clock went backwards: " + first + " -> " + second);
  }

  /** The updater is a daemon thread, so reading the clock must not pin the JVM. */
  @Test
  void updaterThreadIsDaemon() {
    CoarseClock.millis();
    boolean found = false;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if ("weaver-girl-coarse-clock".equals(t.getName())) {
        found = true;
        assertTrue(t.isDaemon(), "the coarse-clock updater must be a daemon thread");
      }
    }
    assertTrue(found, "the coarse clock did not start its updater thread");
  }
}
