package com.github.cc11001100.weavergirl.core.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Periodic remote version check for the agent (P80).
 *
 * <p>Polls a JSON version endpoint (see {@link VersionInfo}) on a daemon
 * scheduler. When the remote version is newer than
 * {@link ReleaseInfo#getVersion()}, registered {@link Consumer} listeners are
 * notified with the {@link VersionInfo}. Check-only by design: downloading and
 * applying the new artifact is left to the operator or to
 * {@link AgentUpdater}.</p>
 *
 * <p>Failures (network, malformed body, dev SNAPSHOT versions) never throw —
 * they increment {@link #getFailedCheckCount()} and are logged at warn level,
 * so the check can never destabilize the agent.</p>
 *
 * @since 1.10.0
 */
public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);

    private static final long DEFAULT_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    private final String endpoint;
    private final String currentVersion;
    private final long checkIntervalMs;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private final List<Consumer<VersionInfo>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();

    private final AtomicLong checkCount = new AtomicLong();
    private final AtomicLong failedCheckCount = new AtomicLong();
    private final AtomicReference<VersionInfo> latestAvailable = new AtomicReference<>();

    private UpdateChecker(Builder builder) {
        this.endpoint = builder.endpoint;
        this.currentVersion = builder.currentVersion != null
                ? builder.currentVersion : ReleaseInfo.getInstance().getVersion();
        this.checkIntervalMs = builder.checkIntervalMs > 0
                ? builder.checkIntervalMs : DEFAULT_CHECK_INTERVAL_MS;
        this.connectTimeoutMs = builder.connectTimeoutMs > 0
                ? builder.connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = builder.readTimeoutMs > 0
                ? builder.readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "weaver-girl-update-checker");
            t.setDaemon(true);
            return t;
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Register a listener notified when a newer version is found. */
    public UpdateChecker addListener(Consumer<VersionInfo> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    public UpdateChecker removeListener(Consumer<VersionInfo> listener) {
        listeners.remove(listener);
        return this;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduledTask.set(scheduler.scheduleAtFixedRate(
                    this::checkNowQuiet, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS));
            log.info("[Update] Checker started (endpoint={}, interval={}ms, current={})",
                    endpoint, checkIntervalMs, currentVersion);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            ScheduledFuture<?> task = scheduledTask.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[Update] Checker stopped (checks={}, failed={})",
                    checkCount.get(), failedCheckCount.get());
        }
    }

    /**
     * Run one check immediately.
     *
     * @return the remote version info when a newer version exists, else null
     */
    public VersionInfo checkNow() {
        checkCount.incrementAndGet();
        try {
            String body = fetchBody(endpoint);
            VersionInfo remote = VersionInfo.parseJson(body);
            if (remote == null) {
                failedCheckCount.incrementAndGet();
                log.warn("[Update] Unparseable version response from {}", endpoint);
                return null;
            }
            if (VersionInfo.isNewerThan(remote.getVersion(), currentVersion)) {
                latestAvailable.set(remote);
                log.info("[Update] New version available: {} (current {},{}{})",
                        remote.getVersion(), currentVersion,
                        remote.isMandatory() ? ", MANDATORY" : "",
                        remote.isDownloadable() ? ", downloadable" : ", check-only");
                for (Consumer<VersionInfo> listener : listeners) {
                    try {
                        listener.accept(remote);
                    } catch (Exception e) {
                        log.warn("[Update] Listener failed: {}", e.getMessage());
                    }
                }
                return remote;
            }
            log.debug("[Update] Current version {} is up to date", currentVersion);
            return null;
        } catch (Exception e) {
            failedCheckCount.incrementAndGet();
            log.warn("[Update] Version check failed: {}", e.getMessage());
            return null;
        }
    }

    private void checkNowQuiet() {
        try {
            checkNow();
        } catch (Throwable t) {
            failedCheckCount.incrementAndGet();
            log.warn("[Update] Scheduled check failed: {}", t.getMessage());
        }
    }

    /**
     * Fetch the endpoint body. Visible for testing (subclass override point).
     */
    String fetchBody(String endpoint) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    public boolean isRunning() { return running.get(); }
    public long getCheckCount() { return checkCount.get(); }
    public long getFailedCheckCount() { return failedCheckCount.get(); }
    public VersionInfo getLatestAvailable() { return latestAvailable.get(); }
    public String getEndpoint() { return endpoint; }
    public String getCurrentVersion() { return currentVersion; }
    public long getCheckIntervalMs() { return checkIntervalMs; }

    public static final class Builder {
        private String endpoint;
        private String currentVersion;
        private long checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

        /** Version endpoint URL (required). */
        public Builder endpoint(String v) { this.endpoint = v; return this; }
        /** Override the "current" version (default: ReleaseInfo). */
        public Builder currentVersion(String v) { this.currentVersion = v; return this; }
        public Builder checkIntervalMs(long v) { this.checkIntervalMs = v; return this; }
        public Builder connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }

        public UpdateChecker build() {
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new IllegalStateException("endpoint must be set");
            }
            return new UpdateChecker(this);
        }
    }
}
