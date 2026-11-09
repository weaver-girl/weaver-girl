package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HealthCheckExtension} is the shape an extension uses to report its own health; these tests
 * pin the adapter behavior {@link HealthChecks} promises for it: naming, isolation, ordering against
 * standalone checks, and accounting.
 */
class HealthCheckExtensionTest {

  @AfterEach
  void reset() {
    HealthChecks.clear();
    ExtensionPerformanceTracker.clear();
  }

  @Test
  void anExtensionWithNoOverrideIsUpAndUsesItsClassName() {
    HealthChecks.register(new HealthCheckExtension() {});

    HealthReport report = HealthChecks.checkAll();

    assertEquals(1, report.getComponentCount());
    String component = report.getComponents().keySet().iterator().next();
    assertTrue(
        component.endsWith("HealthCheckExtensionTest$1"),
        "the default name is the implementation's class name, was: " + component);
    assertEquals(HealthCheckResult.Status.UP, report.getOverallStatus());
  }

  @Test
  void theExtensionNameIsTheComponentName() {
    HealthChecks.register(extension("kafka-sink", HealthCheckResult::up));

    assertEquals(
        HealthCheckResult.Status.UP,
        HealthChecks.checkAll().getComponents().get("kafka-sink").getStatus());
    assertEquals(1, HealthChecks.getExtensions().size());
    assertEquals(0, HealthChecks.getChecks().size());
  }

  @Test
  void aNamePrefixQualifiesTheComponentSoTwoPluginsCanReportTheSameComponent() {
    HealthChecks.register("plugin-a", extension("backend", HealthCheckResult::up));
    HealthChecks.register("plugin-b", extension("backend", () -> HealthCheckResult.down("gone")));

    HealthReport report = HealthChecks.checkAll();

    assertEquals("[plugin-a/backend, plugin-b/backend]", report.getComponents().keySet().toString());
    assertEquals(HealthCheckResult.Status.DOWN, report.getOverallStatus());
  }

  @Test
  void aThrowingOrNullExtensionIsDownAndTheOthersStillRun() {
    HealthChecks.register(
        extension(
            "throws",
            () -> {
              throw new IllegalStateException("boom");
            }));
    HealthChecks.register(extension("null", () -> null));
    HealthChecks.register(extension("fine", HealthCheckResult::up));

    HealthReport report = HealthChecks.checkAll();

    assertEquals(3, report.getComponentCount());
    assertEquals(HealthCheckResult.Status.DOWN, report.getComponents().get("throws").getStatus());
    assertEquals(
        "java.lang.IllegalStateException",
        report.getComponents().get("throws").getDetails().get("error"));
    assertEquals(HealthCheckResult.Status.DOWN, report.getComponents().get("null").getStatus());
    assertEquals(HealthCheckResult.Status.UP, report.getComponents().get("fine").getStatus());
  }

  @Test
  void standaloneChecksAreEvaluatedBeforeExtensions() {
    HealthChecks.register(extension("ext", HealthCheckResult::up));
    HealthChecks.register(fixedCheck("probe", HealthCheckResult.up()));

    assertEquals(
        "[probe, ext]", HealthChecks.checkAll().getComponents().keySet().toString());
  }

  @Test
  void aDuplicateComponentNameLeavesTheFirstAnswering() {
    HealthChecks.register(extension("backend", HealthCheckResult::up));
    HealthChecks.register(extension("backend", () -> HealthCheckResult.down("second")));

    assertEquals(2, HealthChecks.getExtensions().size());
    assertEquals(
        HealthCheckResult.Status.UP,
        HealthChecks.checkAll().getComponents().get("backend").getStatus());
  }

  @Test
  void registeringTheSameExtensionTwiceAddsItOnce() {
    HealthCheckExtension extension = extension("backend", HealthCheckResult::up);

    HealthChecks.register(extension);
    HealthChecks.register(extension);

    assertEquals(1, HealthChecks.getExtensions().size());
  }

  @Test
  void unregisteringRemovesTheComponent() {
    HealthCheckExtension extension = extension("backend", HealthCheckResult::up);
    HealthChecks.register(extension);

    HealthChecks.unregister(extension);

    assertTrue(HealthChecks.isEmpty());
    assertEquals(0, HealthChecks.checkAll().getComponentCount());
  }

  @Test
  void isEmptyTracksChecksAndExtensions() {
    assertTrue(HealthChecks.isEmpty());

    HealthChecks.register(extension("backend", HealthCheckResult::up));
    assertFalse(HealthChecks.isEmpty());

    HealthChecks.clear();
    HealthChecks.register(fixedCheck("probe", HealthCheckResult.up()));
    assertFalse(HealthChecks.isEmpty());
  }

  @Test
  void evaluatingAnExtensionIsCountedAsHealthCheckExtensionWork() {
    HealthChecks.register(extension("backend", HealthCheckResult::up));
    HealthChecks.register(
        extension(
            "broken",
            () -> {
              throw new IllegalStateException("boom");
            }));

    HealthChecks.checkAll();

    ExtensionStats healthy =
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK, "backend");
    assertNotNull(healthy, "a successful evaluation must be counted");
    assertEquals(1, healthy.getInvocations());
    assertEquals(0, healthy.getErrors());

    ExtensionStats broken =
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_HEALTH_CHECK, "broken");
    assertNotNull(broken);
    assertEquals(1, broken.getInvocations());
    assertEquals(1, broken.getErrors());
    assertEquals(1.0, broken.getErrorRate());
  }

  @Test
  void anEmptyRegistryIsUp() {
    assertTrue(HealthChecks.checkAll().isUp());
  }

  private static HealthCheckExtension extension(
      String name, Supplier<HealthCheckResult> body) {
    return new HealthCheckExtension() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public HealthCheckResult checkHealth() {
        return body.get();
      }
    };
  }

  private static HealthCheck fixedCheck(String name, HealthCheckResult result) {
    return new HealthCheck() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public HealthCheckResult check() {
        return result;
      }
    };
  }
}
