package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.PluginMetadata;
import com.github.cc11001100.weavergirl.core.plugin.PluginCompatibilityChecker.CompatibilityReport;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PluginCompatibilityCheckerTest {

  private PluginCompatibilityChecker checker;

  @BeforeEach
  void setUp() {
    checker = new PluginCompatibilityChecker("1.0.0", "1.1.0");
  }

  // --- Version Comparison ---

  @Test
  void compareVersions_equalVersions() {
    assertEquals(0, PluginCompatibilityChecker.compareVersions("1.0.0", "1.0.0"));
  }

  @Test
  void compareVersions_greaterMajor() {
    assertTrue(PluginCompatibilityChecker.compareVersions("2.0.0", "1.0.0") > 0);
  }

  @Test
  void compareVersions_lesserMajor() {
    assertTrue(PluginCompatibilityChecker.compareVersions("1.0.0", "2.0.0") < 0);
  }

  @Test
  void compareVersions_greaterMinor() {
    assertTrue(PluginCompatibilityChecker.compareVersions("1.1.0", "1.0.0") > 0);
  }

  @Test
  void compareVersions_greaterPatch() {
    assertTrue(PluginCompatibilityChecker.compareVersions("1.0.1", "1.0.0") > 0);
  }

  @Test
  void compareVersions_differentLengths() {
    assertTrue(PluginCompatibilityChecker.compareVersions("1.0.0", "1.0") == 0);
    assertTrue(PluginCompatibilityChecker.compareVersions("1.0.1", "1.0") > 0);
  }

  @Test
  void compareVersions_snapshotSuffix() {
    assertTrue(PluginCompatibilityChecker.compareVersions("1.0.0-SNAPSHOT", "1.0.0") == 0);
    assertTrue(PluginCompatibilityChecker.compareVersions("2.0.0-SNAPSHOT", "1.0.0") > 0);
  }

  @Test
  void compareVersions_nullTreatedAsZero() {
    assertTrue(PluginCompatibilityChecker.compareVersions(null, "0.0.0") == 0);
    assertTrue(PluginCompatibilityChecker.compareVersions("1.0.0", null) > 0);
  }

  // --- Version Validation ---

  @Test
  void isValidVersion_standard() {
    assertTrue(PluginCompatibilityChecker.isValidVersion("1.0.0"));
  }

  @Test
  void isValidVersion_twoParts() {
    assertTrue(PluginCompatibilityChecker.isValidVersion("1.0"));
  }

  @Test
  void isValidVersion_snapshot() {
    assertTrue(PluginCompatibilityChecker.isValidVersion("1.0.0-SNAPSHOT"));
  }

  @Test
  void isValidVersion_preRelease() {
    assertTrue(PluginCompatibilityChecker.isValidVersion("1.0.0-beta.1"));
  }

  @Test
  void isValidVersion_buildMetadata() {
    assertTrue(PluginCompatibilityChecker.isValidVersion("1.0.0+build.123"));
  }

  @Test
  void isValidVersion_null() {
    assertFalse(PluginCompatibilityChecker.isValidVersion(null));
  }

  @Test
  void isValidVersion_empty() {
    assertFalse(PluginCompatibilityChecker.isValidVersion(""));
  }

  @Test
  void isValidVersion_textOnly() {
    assertFalse(PluginCompatibilityChecker.isValidVersion("abc"));
  }

  // --- Compatibility Checks ---

  @Test
  void check_compatiblePlugin_passes() {
    PluginMetadata meta = PluginMetadata.builder().name("test-plugin").version("1.0.0").build();

    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertTrue(report.isCompatible());
    assertFalse(report.isAutoDisabled());
    assertTrue(report.getErrors().isEmpty());
  }

  @Test
  void check_requiresHigherAgentVersion_fails() {
    PluginMetadata meta =
        PluginMetadata.builder()
            .name("advanced-plugin")
            .version("1.0.0")
            .minimumAgentVersion("2.0.0")
            .build();

    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertFalse(report.isCompatible());
    assertTrue(report.isAutoDisabled());
    assertTrue(report.getErrors().get(0).contains("requires agent version"));
  }

  @Test
  void check_requiresSameAgentVersion_passes() {
    PluginMetadata meta =
        PluginMetadata.builder()
            .name("compatible-plugin")
            .version("1.0.0")
            .minimumAgentVersion("1.0.0")
            .build();

    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertTrue(report.isCompatible());
  }

  @Test
  void check_requiresLowerAgentVersion_passes() {
    PluginMetadata meta =
        PluginMetadata.builder()
            .name("legacy-plugin")
            .version("1.0.0")
            .minimumAgentVersion("0.9.0")
            .build();

    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertTrue(report.isCompatible());
  }

  @Test
  void check_duplicateSameVersion_fails() {
    PluginMetadata meta1 = PluginMetadata.builder().name("my-plugin").version("1.0.0").build();
    PluginMetadata meta2 = PluginMetadata.builder().name("my-plugin").version("1.0.0").build();

    checker.check(meta1, Collections.emptySet());
    CompatibilityReport report2 = checker.check(meta2, Collections.emptySet());
    assertFalse(report2.isCompatible());
    assertTrue(report2.getErrors().get(0).contains("duplicates"));
  }

  @Test
  void check_duplicateOlderVersion_fails() {
    PluginMetadata meta1 = PluginMetadata.builder().name("my-plugin").version("2.0.0").build();
    PluginMetadata meta2 = PluginMetadata.builder().name("my-plugin").version("1.0.0").build();

    checker.check(meta1, Collections.emptySet());
    CompatibilityReport report2 = checker.check(meta2, Collections.emptySet());
    assertFalse(report2.isCompatible());
    assertTrue(report2.getErrors().get(0).contains("duplicates"));
  }

  @Test
  void check_duplicateNewerVersion_warns() {
    PluginMetadata meta1 = PluginMetadata.builder().name("my-plugin").version("1.0.0").build();
    PluginMetadata meta2 = PluginMetadata.builder().name("my-plugin").version("2.0.0").build();

    checker.check(meta1, Collections.emptySet());
    CompatibilityReport report2 = checker.check(meta2, Collections.emptySet());
    assertTrue(report2.isCompatible());
    assertFalse(report2.getWarnings().isEmpty());
    assertTrue(report2.getWarnings().get(0).contains("supersedes"));
  }

  @Test
  void check_missingDependency_fails() {
    PluginMetadata meta =
        PluginMetadata.builder()
            .name("dependent-plugin")
            .version("1.0.0")
            .depend("nonexistent-plugin")
            .build();

    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertFalse(report.isCompatible());
    assertTrue(
        report.getErrors().stream()
            .anyMatch(e -> e.contains("depends on") && e.contains("not available")));
  }

  @Test
  void check_dependencyPresent_passes() {
    PluginMetadata meta =
        PluginMetadata.builder().name("dependent-plugin").version("1.0.0").depend("jdbc").build();

    CompatibilityReport report = checker.check(meta, new HashSet<>(Arrays.asList("jdbc", "redis")));
    assertTrue(report.isCompatible());
  }

  @Test
  void check_invalidVersionFormat_warns() {
    PluginMetadata meta =
        PluginMetadata.builder().name("bad-version-plugin").version("not-a-version").build();

    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertTrue(report.isCompatible()); // Warning only, not error
    assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("invalid version")));
  }

  @Test
  void check_multipleIssues_reportsAll() {
    PluginMetadata meta =
        PluginMetadata.builder()
            .name("problematic-plugin")
            .version("not-valid")
            .minimumAgentVersion("99.0.0")
            .depend("missing-dep")
            .build();

    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertFalse(report.isCompatible());
    assertTrue(report.getErrors().size() >= 2); // agent version + missing dep
    assertTrue(report.getWarnings().size() >= 1); // invalid version format
  }

  // --- Report Collection ---

  @Test
  void getReports_returnsAllReports() {
    checker.check(
        PluginMetadata.builder().name("p1").version("1.0.0").build(), Collections.emptySet());
    checker.check(
        PluginMetadata.builder().name("p2").version("1.0.0").build(), Collections.emptySet());
    assertEquals(2, checker.getReports().size());
  }

  @Test
  void getIncompatibleReports_filtersCorrectly() {
    checker.check(
        PluginMetadata.builder().name("good").version("1.0.0").build(), Collections.emptySet());
    checker.check(
        PluginMetadata.builder().name("bad").version("1.0.0").minimumAgentVersion("99.0.0").build(),
        Collections.emptySet());
    assertEquals(1, checker.getIncompatibleReports().size());
    assertEquals("bad", checker.getIncompatibleReports().get(0).getPluginName());
  }

  @Test
  void getCheckedCount_increments() {
    assertEquals(0, checker.getCheckedCount());
    checker.check(
        PluginMetadata.builder().name("p1").version("1.0.0").build(), Collections.emptySet());
    assertEquals(1, checker.getCheckedCount());
  }

  @Test
  void getCompatibleCount_countsCorrectly() {
    checker.check(
        PluginMetadata.builder().name("p1").version("1.0.0").build(), Collections.emptySet());
    checker.check(
        PluginMetadata.builder().name("p2").version("1.0.0").minimumAgentVersion("99.0.0").build(),
        Collections.emptySet());
    assertEquals(1, checker.getCompatibleCount());
  }

  // --- Auto-disable Config ---

  @Test
  void autoDisableEnabled_incompatiblePluginIsAutoDisabled() {
    PluginCompatibilityChecker autoChecker = new PluginCompatibilityChecker("1.0.0", "1.1.0", true);
    PluginMetadata meta =
        PluginMetadata.builder().name("p").version("1.0.0").minimumAgentVersion("99.0.0").build();
    CompatibilityReport report = autoChecker.check(meta, Collections.emptySet());
    assertTrue(report.isAutoDisabled());
  }

  @Test
  void autoDisableDisabled_incompatiblePluginIsNotAutoDisabled() {
    PluginCompatibilityChecker manualChecker =
        new PluginCompatibilityChecker("1.0.0", "1.1.0", false);
    PluginMetadata meta =
        PluginMetadata.builder().name("p").version("1.0.0").minimumAgentVersion("99.0.0").build();
    CompatibilityReport report = manualChecker.check(meta, Collections.emptySet());
    assertFalse(report.isCompatible());
    assertFalse(report.isAutoDisabled());
  }

  // --- Getters ---

  @Test
  void getAgentVersion_returnsConfigured() {
    assertEquals("1.0.0", checker.getAgentVersion());
  }

  @Test
  void getApiVersion_returnsConfigured() {
    assertEquals("1.1.0", checker.getApiVersion());
  }

  @Test
  void isAutoDisableIncompatible_returnsTrue() {
    assertTrue(checker.isAutoDisableIncompatible());
  }

  @Test
  void constructor_nullVersions_useDefaults() {
    PluginCompatibilityChecker c = new PluginCompatibilityChecker(null, null);
    assertEquals("1.0.0", c.getAgentVersion());
    assertEquals("1.0.0", c.getApiVersion());
  }

  @Test
  void compatibilityReport_toString_containsInfo() {
    PluginMetadata meta = PluginMetadata.builder().name("test").version("1.0.0").build();
    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    String str = report.toString();
    assertTrue(str.contains("test"));
    assertTrue(str.contains("1.0.0"));
    assertTrue(str.contains("compatible=true"));
  }

  @Test
  void report_errorsAreUnmodifiable() {
    PluginMetadata meta =
        PluginMetadata.builder().name("p").version("1.0.0").minimumAgentVersion("99.0.0").build();
    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertThrows(UnsupportedOperationException.class, () -> report.getErrors().add("new error"));
  }

  @Test
  void report_warningsAreUnmodifiable() {
    PluginMetadata meta = PluginMetadata.builder().name("p").version("not-valid").build();
    CompatibilityReport report = checker.check(meta, Collections.emptySet());
    assertThrows(
        UnsupportedOperationException.class, () -> report.getWarnings().add("new warning"));
  }
}
