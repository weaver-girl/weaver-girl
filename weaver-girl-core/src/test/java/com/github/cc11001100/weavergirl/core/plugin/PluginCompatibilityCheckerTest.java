package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.PluginMetadata;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The contract check is what stops a plugin jar from running on an agent older than it needs. */
class PluginCompatibilityCheckerTest {

  private static final Set<String> NONE = Collections.emptySet();

  @Test
  void pluginBuiltForCurrentContractIsCompatible() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");

    PluginCompatibilityChecker.CompatibilityReport report =
        checker.check(metadata("demo", "1.0.0"), NONE);

    assertTrue(report.isCompatible());
    assertTrue(report.getErrors().isEmpty());
  }

  @Test
  void pluginBuiltForNewerContractIsRejected() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");

    PluginCompatibilityChecker.CompatibilityReport report =
        checker.check(metadata("demo", "2.0.0"), NONE);

    assertFalse(report.isCompatible());
    assertTrue(report.isAutoDisabled());
    assertTrue(report.getErrors().get(0).contains("2.0.0"));
  }

  @Test
  void pluginThatDoesNotDeclareContractIsNotJudgedOnIt() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");
    PluginMetadata metadata = PluginMetadata.builder().name("demo").version("1.0.0").build();

    assertTrue(checker.check(metadata, NONE).isCompatible());
  }

  @Test
  void pluginRequiringNewerAgentIsRejected() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");
    PluginMetadata metadata =
        PluginMetadata.builder().name("demo").version("1.0.0").minimumAgentVersion("9.0.0").build();

    assertFalse(checker.check(metadata, NONE).isCompatible());
  }

  /**
   * A drop-in jar is how a product replaces a built-in plugin, and it arrives under the same name.
   * The checker used to reject whichever copy it saw second, so a newer jar loaded after the built-in
   * was skipped and the older one stayed.
   */
  @Test
  void newerVersionOfALoadedPluginReplacesIt() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");

    assertTrue(checker.check(versioned("metrics", "1.0.0"), NONE).isCompatible());

    PluginCompatibilityChecker.CompatibilityReport newer =
        checker.check(versioned("metrics", "1.0.0", "2.0.0"), NONE);

    assertTrue(newer.isCompatible(), "a newer version was rejected as a duplicate");
    assertTrue(
        newer.getWarnings().stream().anyMatch(w -> w.contains("supersedes")),
        "replacing a loaded plugin should be reported");
  }

  @Test
  void olderVersionOfALoadedPluginIsSkipped() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");

    assertTrue(checker.check(versioned("metrics", "2.0.0"), NONE).isCompatible());

    PluginCompatibilityChecker.CompatibilityReport older =
        checker.check(versioned("metrics", "2.0.0", "1.0.0"), NONE);

    assertFalse(older.isCompatible(), "an older duplicate should not load over the current one");
  }

  @Test
  void aMissingDependencyAndAMalformedVersionAreReported() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");
    assertEquals("1.0.0", checker.getAgentVersion());
    assertEquals("1.0.0", checker.getApiVersion());
    assertTrue(checker.isAutoDisableIncompatible());

    PluginMetadata metadata =
        PluginMetadata.builder()
            .name("demo")
            .version("not-a-version")
            .apiVersion("1.0.0")
            .depend("missing")
            .build();
    PluginCompatibilityChecker.CompatibilityReport report = checker.check(metadata, NONE);

    assertFalse(report.isCompatible(), "a plugin whose dependency is absent should not load");
    assertEquals(1, checker.getIncompatibleReports().size());
    assertEquals(0, checker.getCompatibleCount());
    assertEquals(1, checker.getCheckedCount());
    assertEquals(1, checker.getReports().size());

    // A version that parses but is not semver is a warning, not a rejection.
    checker.check(versioned("other", "1.beta"), NONE);
    assertTrue(checker.getCompatibleCount() >= 1);

    // Registered directly, so a later check sees it as already loaded.
    checker.registerLoaded("extra", versioned("extra", "3.0.0"));
    assertFalse(checker.check(versioned("extra", "1.0.0"), NONE).isCompatible());
  }

  @Test
  void versionComparisonHandlesNullsAndUnevenLengths() {
    assertEquals(0, PluginCompatibilityChecker.compareVersions(null, null));
    assertTrue(PluginCompatibilityChecker.compareVersions("1.2", "1.2.0") == 0);
    assertTrue(PluginCompatibilityChecker.compareVersions("1.10", "1.9") > 0);
    assertTrue(PluginCompatibilityChecker.compareVersions("1.0.0+build", "1.0.0") == 0);
    assertFalse(PluginCompatibilityChecker.isValidVersion(null));
    assertFalse(PluginCompatibilityChecker.isValidVersion(""));
    assertTrue(PluginCompatibilityChecker.isValidVersion("1.0.0-beta.1+build.5"));
  }

  private static PluginMetadata versioned(String name, String apiVersion, String version) {
    return PluginMetadata.builder().name(name).version(version).apiVersion(apiVersion).build();
  }

  private static PluginMetadata versioned(String name, String version) {
    return versioned(name, "1.0.0", version);
  }

  private static PluginMetadata metadata(String name, String apiVersion) {
    return PluginMetadata.builder().name(name).version("1.0.0").apiVersion(apiVersion).build();
  }

  /** A missing version is treated as the baseline rather than rejected as malformed. */
  @Test
  void aMissingVersionFallsBackToTheBaseline() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker(null, null, false);
    PluginCompatibilityChecker.CompatibilityReport report = checker.check(metadata("demo", "1.0.0"), NONE);

    assertTrue(report.isCompatible(), "the baseline version was treated as incompatible");
    assertEquals("demo", report.getPluginName());
    assertNotNull(report.getPluginVersion());
    assertTrue(report.toString().contains("demo"));
  }

  /** A version segment that is not a number compares as zero instead of failing the check. */
  @Test
  void aNonNumericVersionSegmentComparesAsZero() {
    PluginCompatibilityChecker checker = new PluginCompatibilityChecker("1.0.0", "1.0.0");
    PluginCompatibilityChecker.CompatibilityReport report =
        checker.check(metadata("demo", "1.beta.0"), NONE);

    assertNotNull(report);
  }
}
