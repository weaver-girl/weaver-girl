package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Covers the result value type: status parsing, factories and detail sanitising. */
class HealthCheckResultTest {

  @Test
  void statusParseRecognisesNamesAndFallsBackToUnknown() {
    assertEquals(HealthCheckResult.Status.UP, HealthCheckResult.Status.parse("up"));
    assertEquals(HealthCheckResult.Status.DEGRADED, HealthCheckResult.Status.parse(" Degraded "));
    assertEquals(HealthCheckResult.Status.UNKNOWN, HealthCheckResult.Status.parse(null));
    assertEquals(HealthCheckResult.Status.UNKNOWN, HealthCheckResult.Status.parse("nope"));
  }

  @Test
  void upWithDetailsKeepsOnlyCompleteEntries() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("pool", "ok");
    details.put(null, "dropped");
    details.put("blank", null);

    HealthCheckResult result = HealthCheckResult.up(details);

    assertTrue(result.isUp());
    assertEquals("ok", result.getDetails().get("pool"));
    assertEquals(1, result.getDetails().size());
    assertThrows(UnsupportedOperationException.class, () -> result.getDetails().put("x", "y"));
  }

  @Test
  void nullDetailsBecomeEmpty() {
    HealthCheckResult result = HealthCheckResult.down((Map<String, String>) null);

    assertEquals(HealthCheckResult.Status.DOWN, result.getStatus());
    assertFalse(result.isUp());
    assertTrue(result.getDetails().isEmpty());
  }

  @Test
  void reasonFactoriesSkipBlankReasons() {
    assertEquals("slow", HealthCheckResult.degraded("slow").getDetails().get("reason"));
    assertTrue(HealthCheckResult.degraded("   ").getDetails().isEmpty());
    assertTrue(HealthCheckResult.unknown(null).getDetails().isEmpty());
  }

  @Test
  void degradedAndOfAcceptDetailMaps() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("lag", "3");

    assertEquals("3", HealthCheckResult.degraded(details).getDetails().get("lag"));
    assertEquals(
        HealthCheckResult.Status.UNKNOWN, HealthCheckResult.of(null, details).getStatus());
  }

  @Test
  void toStringNamesTheStatus() {
    assertTrue(HealthCheckResult.up().toString().contains("status=UP"));
  }
}
