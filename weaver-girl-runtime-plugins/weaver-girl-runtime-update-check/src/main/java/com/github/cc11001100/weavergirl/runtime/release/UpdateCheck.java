package com.github.cc11001100.weavergirl.runtime.release;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the periodic version checker from configuration and owns its lifecycle.
 *
 * <p>Nothing is created unless {@code updateCheckEndpoint} is set, so the default configuration costs
 * no background thread. The checker polls that endpoint and, when a newer version is published, logs
 * it. With {@code updateAutoStage=true} and a descriptor that carries a download URL plus checksum,
 * the artifact is downloaded and verified into the staging directory, ready for an operator to swap in
 * and restart — a loaded agent jar cannot replace itself.
 *
 * <p>Optional tuning keys: {@code updateCheckIntervalMs} (default 24h), {@code updateStagingDir}
 * (default {@code java.io.tmpdir/weaver-girl-updates}), {@code updateHeaders} (semicolon separated
 * {@code name=value} pairs sent with the download).
 */
public final class UpdateCheck {

  private static final Logger log = LoggerFactory.getLogger(UpdateCheck.class);

  private UpdateChecker checker;

  /**
   * Start polling for this configuration. Returns false, creating nothing, when {@code
   * updateCheckEndpoint} is absent.
   */
  public boolean start(Map<String, String> config) {
    if (config == null) {
      return false;
    }
    String endpoint = config.get("updateCheckEndpoint");
    if (endpoint == null || endpoint.trim().isEmpty()) {
      return false;
    }
    try {
      UpdateChecker.Builder builder = UpdateChecker.builder().endpoint(endpoint.trim());
      String interval = config.get("updateCheckIntervalMs");
      if (interval != null) {
        try {
          builder.checkIntervalMs(Long.parseLong(interval.trim()));
        } catch (NumberFormatException e) {
          log.warn("Invalid updateCheckIntervalMs '{}', using default", interval);
        }
      }
      UpdateChecker created = builder.build();
      boolean autoStage = "true".equalsIgnoreCase(config.get("updateAutoStage"));
      String stagingDir = config.get("updateStagingDir");
      String headers = config.get("updateHeaders");
      created.addListener(remote -> onNewVersionAvailable(remote, autoStage, stagingDir, headers));
      created.start();
      checker = created;
      log.info("Update check enabled (endpoint={})", endpoint.trim());
      return true;
    } catch (Exception e) {
      log.warn("Failed to initialize update check: {}", e.getMessage());
      return false;
    }
  }

  /** The running checker, or null when {@link #start} did not enable one. */
  public UpdateChecker checker() {
    return checker;
  }

  /** Stop the checker. Safe to call when nothing was started. */
  public void stop() {
    if (checker != null) {
      try {
        checker.stop();
      } catch (Exception e) {
        log.warn("Failed to stop update checker: {}", e.getMessage());
      }
      checker = null;
    }
  }

  private static void onNewVersionAvailable(
      VersionInfo remote, boolean autoStage, String stagingDir, String headers) {
    String notes = remote.getReleaseNotes();
    log.warn(
        "[Update] New agent version available: {}{} — see release notes{}",
        remote.getVersion(),
        remote.isMandatory() ? " (MANDATORY)" : "",
        notes != null ? ": " + notes : " (none provided)");
    if (!autoStage) {
      if (remote.isDownloadable()) {
        log.warn(
            "[Update] Download manually: {} (set updateAutoStage=true to stage automatically)",
            remote.getDownloadUrl());
      }
      return;
    }
    if (!remote.isDownloadable()) {
      log.warn(
          "[Update] updateAutoStage=true but v{} has no downloadUrl — manual upgrade required",
          remote.getVersion());
      return;
    }
    try {
      File dir =
          stagingDir != null && !stagingDir.trim().isEmpty()
              ? new File(stagingDir.trim())
              : new File(System.getProperty("java.io.tmpdir"), "weaver-girl-updates");
      AgentUpdater updater = new AgentUpdater(dir);
      Map<String, String> extraHeaders = null;
      if (headers != null && !headers.trim().isEmpty()) {
        extraHeaders = new LinkedHashMap<>();
        for (String pair : headers.split(";")) {
          int eq = pair.indexOf('=');
          if (eq > 0) {
            extraHeaders.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
          }
        }
      }
      AgentUpdater.UpdateResult result = updater.stageUpdate(remote, extraHeaders);
      if (!result.isSuccess()) {
        log.warn("[Update] Auto-stage failed: {}", result.getMessage());
      }
    } catch (Exception e) {
      log.warn("[Update] Auto-stage failed: {}", e.getMessage());
    }
  }
}
