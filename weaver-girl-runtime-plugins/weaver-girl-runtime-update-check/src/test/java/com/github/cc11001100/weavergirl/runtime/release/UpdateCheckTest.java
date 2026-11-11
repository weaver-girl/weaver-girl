package com.github.cc11001100.weavergirl.runtime.release;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Lifecycle wiring and the version-available branch matrix of {@link UpdateCheck}. */
class UpdateCheckTest {

  @Test
  void startIsOffWithoutAnEndpoint() {
    assertFalse(new UpdateCheck().start(null));

    Map<String, String> blank = new HashMap<>();
    blank.put("updateCheckEndpoint", "   ");
    assertFalse(new UpdateCheck().start(blank));
  }

  @Test
  void startAppliesTuningKeysAndStopsCleanly() {
    Map<String, String> config = new HashMap<>();
    config.put("updateCheckEndpoint", "http://127.0.0.1:1/version.json");
    config.put("updateCheckIntervalMs", "60000");
    config.put("updateAutoStage", "false");

    UpdateCheck check = new UpdateCheck();
    try {
      assertTrue(check.start(config));
      assertNotNull(check.checker());
      assertTrue(check.checker().isRunning());
    } finally {
      check.stop();
      check.stop(); // idempotent
    }
    assertNull(check.checker());
  }

  @Test
  void malformedTuningKeysFallBackToDefaults() {
    Map<String, String> config = new HashMap<>();
    config.put("updateCheckEndpoint", "http://127.0.0.1:1/version.json");
    config.put("updateCheckIntervalMs", "not-a-number");

    UpdateCheck check = new UpdateCheck();
    try {
      assertTrue(check.start(config));
    } finally {
      check.stop();
    }
  }

  @Test
  void nonDownloadableVersionInManualModeIsJustReported() {
    VersionInfo remote = VersionInfo.builder().version("9.9.9").build();

    assertDoesNotThrow(() -> UpdateCheck.onNewVersionAvailable(remote, false, null, null));
  }

  @Test
  void downloadableVersionInManualModeIsReported() {
    VersionInfo remote =
        VersionInfo.builder().version("9.9.9").downloadUrl("http://127.0.0.1:1/a.jar").build();

    assertDoesNotThrow(() -> UpdateCheck.onNewVersionAvailable(remote, false, null, null));
  }

  @Test
  void autoStageWithoutDownloadUrlStaysManual() {
    VersionInfo remote = VersionInfo.builder().version("9.9.9").mandatory(true).build();

    assertDoesNotThrow(() -> UpdateCheck.onNewVersionAvailable(remote, true, null, null));
  }

  @Test
  void autoStageFailureIsContained() {
    VersionInfo remote =
        VersionInfo.builder().version("9.9.9").downloadUrl("http://127.0.0.1:1/a.jar").build();

    // Null staging dir exercises the tmpdir default; the unreachable URL exercises the failed
    // download path, which must never escape into the caller. Headers include a malformed pair.
    assertDoesNotThrow(
        () ->
            UpdateCheck.onNewVersionAvailable(
                remote, true, null, "Authorization=Bearer x; malformed; X-Y=z"));
  }

  @Test
  void autoStageUsesAnExplicitStagingDirectory() {
    VersionInfo remote =
        VersionInfo.builder()
            .version("9.9.9")
            .downloadUrl("http://127.0.0.1:1/a.jar")
            .sha256("deadbeef")
            .releaseNotes("notes")
            .build();

    String dir =
        System.getProperty("java.io.tmpdir")
            + "/weaver-girl-update-check-test-"
            + System.nanoTime();
    assertDoesNotThrow(
        () -> UpdateCheck.onNewVersionAvailable(remote, true, dir, "X-Token=abc"));
  }
}
