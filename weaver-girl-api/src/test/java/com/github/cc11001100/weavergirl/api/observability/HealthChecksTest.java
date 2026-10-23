package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthChecksTest {

  @BeforeEach
  @AfterEach
  void reset() {
    HealthChecks.clear();
  }

  @Test
  void emptyRegistryIsUpWithNoComponents() {
    HealthReport report = HealthChecks.checkAll();

    assertEquals(HealthCheckResult.Status.UP, report.getOverallStatus());
    assertTrue(report.getComponents().isEmpty());
    assertEquals(0, report.getComponentCount());
    assertTrue(report.isUp());
  }

  @Test
  void checksRunInRegistrationOrderAndExposeDetails() {
    HealthChecks.register(
        check("db", () -> HealthCheckResult.up(java.util.Collections.singletonMap("pool", "ok"))));
    HealthChecks.register(check("cache", () -> HealthCheckResult.degraded("one replica down")));

    HealthReport report = HealthChecks.checkAll();

    assertEquals(2, report.getComponentCount());
    assertEquals(
        "[db, cache]", report.getComponents().keySet().toString(), "registration order is kept");
    assertEquals("ok", report.getComponents().get("db").getDetails().get("pool"));
    assertEquals(
        "one replica down", report.getComponents().get("cache").getDetails().get("reason"));
    assertEquals(HealthCheckResult.Status.DEGRADED, report.getOverallStatus());
  }

  @Test
  void downBeatsDegraded() {
    HealthChecks.register(check("a", () -> HealthCheckResult.degraded("slow")));
    HealthChecks.register(check("b", () -> HealthCheckResult.down("unreachable")));

    assertEquals(HealthCheckResult.Status.DOWN, HealthChecks.checkAll().getOverallStatus());
  }

  @Test
  void unknownIsReportedWhenNothingElseDecides() {
    HealthChecks.register(check("a", () -> HealthCheckResult.unknown("no data yet")));

    assertEquals(HealthCheckResult.Status.UNKNOWN, HealthChecks.checkAll().getOverallStatus());
  }

  @Test
  void aThrowingCheckIsDownAndDoesNotHideTheOthers() {
    HealthChecks.register(
        check(
            "broken",
            () -> {
              throw new IllegalStateException("ping timed out");
            }));
    HealthChecks.register(check("healthy", HealthCheckResult::up));

    HealthReport report = HealthChecks.checkAll();

    assertEquals(HealthCheckResult.Status.DOWN, report.getComponents().get("broken").getStatus());
    assertEquals(
        "ping timed out", report.getComponents().get("broken").getDetails().get("message"));
    assertEquals(HealthCheckResult.Status.UP, report.getComponents().get("healthy").getStatus());
    assertEquals(HealthCheckResult.Status.DOWN, report.getOverallStatus());
  }

  @Test
  void aNullResultIsDown() {
    HealthChecks.register(check("null", () -> null));

    HealthReport report = HealthChecks.checkAll();

    assertEquals(HealthCheckResult.Status.DOWN, report.getComponents().get("null").getStatus());
    assertEquals(
        "check returned null", report.getComponents().get("null").getDetails().get("reason"));
  }

  @Test
  void registeringTheSameCheckTwiceAddsItOnce() {
    HealthCheck check = check("db", HealthCheckResult::up);

    HealthChecks.register(check);
    HealthChecks.register(check);

    assertEquals(1, HealthChecks.getChecks().size());
  }

  @Test
  void theFirstCheckToClaimANameAnswersAndTheNextTakesOverAfterUnregister() {
    HealthCheck first = check("db", HealthCheckResult::up);
    HealthCheck second = check("db", () -> HealthCheckResult.down("second"));

    HealthChecks.register(first);
    HealthChecks.register(second);
    assertEquals(2, HealthChecks.getChecks().size());
    assertEquals(1, HealthChecks.checkAll().getComponentCount());
    assertEquals(HealthCheckResult.Status.UP, HealthChecks.checkAll().getComponents().get("db").getStatus());

    HealthChecks.unregister(first);

    HealthReport report = HealthChecks.checkAll();
    assertEquals(HealthCheckResult.Status.DOWN, report.getComponents().get("db").getStatus());
  }

  private static HealthCheck check(String name, CheckBody body) {
    return new HealthCheck() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public HealthCheckResult check() {
        return body.check();
      }
    };
  }

  private interface CheckBody {
    HealthCheckResult check();
  }
}
