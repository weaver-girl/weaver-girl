package com.github.cc11001100.weavergirl.core.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Staged agent update applier (P80).
 *
 * <p>A Java agent JAR cannot replace itself while loaded — the JVM memory-maps
 * the file. So a fully automatic in-place upgrade is impossible without an
 * external supervisor. This class implements the safe subset:</p>
 * <ol>
 *   <li>Download the new artifact to a staging directory</li>
 *   <li>Verify its SHA-256 checksum against the version descriptor</li>
 *   <li>Report READY with the staged path + restart instructions; the operator
 *       (or supervisor script) swaps the JAR and restarts</li>
 * </ol>
 *
 * <p>State machine: IDLE → DOWNLOADING → VERIFYING → READY (or FAILED).
 * Only one update at a time; a new {@link #stageUpdate} call while busy is
 * rejected. Stale staged files are cleaned by {@link #abort()}.</p>
 *
 * @since 1.10.0
 */
public class AgentUpdater {

    private static final Logger log = LoggerFactory.getLogger(AgentUpdater.class);

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    /** Lifecycle states of a staged update. */
    public enum UpdateState {
        IDLE, DOWNLOADING, VERIFYING, READY, FAILED
    }

    /** Outcome of a staging attempt. */
    public static final class UpdateResult {
        private final boolean success;
        private final UpdateState state;
        private final String message;
        private final File stagedFile;
        private final VersionInfo version;

        private UpdateResult(boolean success, UpdateState state, String message,
                             File stagedFile, VersionInfo version) {
            this.success = success;
            this.state = state;
            this.message = message;
            this.stagedFile = stagedFile;
            this.version = version;
        }

        public static UpdateResult ok(File stagedFile, VersionInfo version, String message) {
            return new UpdateResult(true, UpdateState.READY, message, stagedFile, version);
        }

        public static UpdateResult fail(String message) {
            return new UpdateResult(false, UpdateState.FAILED, message, null, null);
        }

        public boolean isSuccess() { return success; }
        public UpdateState getState() { return state; }
        public String getMessage() { return message; }
        public File getStagedFile() { return stagedFile; }
        public VersionInfo getVersion() { return version; }

        @Override
        public String toString() {
            return "UpdateResult{success=" + success + ", state=" + state
                    + ", message='" + message + "'"
                    + (stagedFile != null ? ", staged=" + stagedFile.getAbsolutePath() : "") + "}";
        }
    }

    private final File stagingDir;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final AtomicReference<UpdateState> state = new AtomicReference<>(UpdateState.IDLE);
    private final AtomicReference<File> stagedFile = new AtomicReference<>();
    private final AtomicReference<VersionInfo> stagedVersion = new AtomicReference<>();

    public AgentUpdater(File stagingDir) {
        this(stagingDir, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public AgentUpdater(File stagingDir, int connectTimeoutMs, int readTimeoutMs) {
        if (stagingDir == null) {
            throw new IllegalArgumentException("stagingDir must not be null");
        }
        this.stagingDir = stagingDir;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    /**
     * Download + verify the artifact described by {@code version} into the
     * staging directory. Synchronous; callers typically run it off the
     * scheduler thread.
     *
     * @param version remote descriptor (must be downloadable)
     * @param extraHeaders optional request headers (may be null)
     * @return staging outcome
     */
    public synchronized UpdateResult stageUpdate(VersionInfo version, Map<String, String> extraHeaders) {
        if (version == null) {
            return UpdateResult.fail("version must not be null");
        }
        if (!version.isDownloadable()) {
            return UpdateResult.fail("version " + version.getVersion()
                    + " has no downloadUrl — check-only mode, manual upgrade required");
        }
        if (!state.compareAndSet(UpdateState.IDLE, UpdateState.DOWNLOADING)
                && !state.compareAndSet(UpdateState.FAILED, UpdateState.DOWNLOADING)
                && !state.compareAndSet(UpdateState.READY, UpdateState.DOWNLOADING)) {
            return UpdateResult.fail("update already in progress (state=" + state.get() + ")");
        }

        try {
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                throw new IllegalStateException("cannot create staging dir: " + stagingDir);
            }
            String fileName = "weaver-girl-agent-" + version.getVersion() + ".jar";
            File target = new File(stagingDir, fileName);

            log.info("[Update] Downloading agent v{} from {}",
                    version.getVersion(), version.getDownloadUrl());
            download(version.getDownloadUrl(), target, extraHeaders);

            state.set(UpdateState.VERIFYING);
            if (version.getSha256() != null && !version.getSha256().trim().isEmpty()) {
                byte[] bytes = Files.readAllBytes(target.toPath());
                String actual = ReleaseInfo.sha256(bytes);
                if (!actual.equalsIgnoreCase(version.getSha256().trim())) {
                    target.delete();
                    state.set(UpdateState.FAILED);
                    String msg = "checksum mismatch for v" + version.getVersion()
                            + " (expected " + version.getSha256() + ", got " + actual + ")";
                    log.error("[Update] {}", msg);
                    return UpdateResult.fail(msg);
                }
                log.info("[Update] Checksum verified for v{}", version.getVersion());
            } else {
                log.warn("[Update] No sha256 in version descriptor — skipping integrity check for v{}",
                        version.getVersion());
            }

            state.set(UpdateState.READY);
            stagedFile.set(target);
            stagedVersion.set(version);
            String msg = "agent v" + version.getVersion() + " staged at " + target.getAbsolutePath()
                    + " — replace the running agent JAR and restart the JVM to apply"
                    + (version.isMandatory() ? " (MANDATORY update)" : "");
            log.info("[Update] {}", msg);
            return UpdateResult.ok(target, version, msg);
        } catch (Exception e) {
            state.set(UpdateState.FAILED);
            log.warn("[Update] Staging failed: {}", e.getMessage());
            return UpdateResult.fail("staging failed: " + e.getMessage());
        }
    }

    /** Download a URL to a file. Visible for testing (subclass override point). */
    void download(String url, File target, Map<String, String> extraHeaders) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Discard the staged artifact and return to IDLE. */
    public synchronized void abort() {
        File f = stagedFile.getAndSet(null);
        stagedVersion.set(null);
        if (f != null && f.exists() && !f.delete()) {
            log.warn("[Update] Could not delete staged file {}", f);
        }
        state.set(UpdateState.IDLE);
    }

    public UpdateState getState() { return state.get(); }
    public File getStagedFile() { return stagedFile.get(); }
    public VersionInfo getStagedVersion() { return stagedVersion.get(); }
    public File getStagingDir() { return stagingDir; }
}
