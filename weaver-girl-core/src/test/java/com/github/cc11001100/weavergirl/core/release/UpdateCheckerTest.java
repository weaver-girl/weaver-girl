package com.github.cc11001100.weavergirl.core.release;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

  private UpdateChecker checker;
  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (checker != null && checker.isRunning()) {
      checker.stop();
      checker = null;
    }
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private String startVersionServer(String body, int status) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/version",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
          exchange.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/version";
  }

  @Test
  void newerVersion_notifiesListeners() throws Exception {
    String endpoint =
        startVersionServer("{\"version\":\"9.9.9\",\"releaseNotes\":\"big release\"}", 200);
    checker = UpdateChecker.builder().endpoint(endpoint).currentVersion("1.0.0").build();

    AtomicReference<VersionInfo> notified = new AtomicReference<>();
    checker.addListener(notified::set);

    VersionInfo result = checker.checkNow();
    assertNotNull(result);
    assertEquals("9.9.9", result.getVersion());
    assertEquals("9.9.9", notified.get().getVersion());
    assertEquals(1, checker.getCheckCount());
    assertEquals(0, checker.getFailedCheckCount());
    assertEquals("9.9.9", checker.getLatestAvailable().getVersion());
  }

  @Test
  void sameVersion_noNotification() throws Exception {
    String endpoint = startVersionServer("{\"version\":\"1.0.0\"}", 200);
    checker = UpdateChecker.builder().endpoint(endpoint).currentVersion("1.0.0").build();

    AtomicReference<VersionInfo> notified = new AtomicReference<>();
    checker.addListener(notified::set);

    assertNull(checker.checkNow());
    assertNull(notified.get());
  }

  @Test
  void olderVersion_noNotification() throws Exception {
    String endpoint = startVersionServer("{\"version\":\"0.1.0\"}", 200);
    checker = UpdateChecker.builder().endpoint(endpoint).currentVersion("1.0.0").build();

    assertNull(checker.checkNow());
  }

  @Test
  void unparseableBody_countsFailure() throws Exception {
    String endpoint = startVersionServer("this is not json", 200);
    checker = UpdateChecker.builder().endpoint(endpoint).currentVersion("1.0.0").build();

    assertNull(checker.checkNow());
    assertEquals(1, checker.getFailedCheckCount());
  }

  @Test
  void serverError_countsFailure() throws Exception {
    String endpoint = startVersionServer("oops", 500);
    checker = UpdateChecker.builder().endpoint(endpoint).currentVersion("1.0.0").build();

    assertNull(checker.checkNow());
    assertEquals(1, checker.getFailedCheckCount());
  }

  @Test
  void unreachableEndpoint_countsFailure() {
    checker =
        UpdateChecker.builder()
            .endpoint("http://127.0.0.1:1/version")
            .currentVersion("1.0.0")
            .connectTimeoutMs(500)
            .readTimeoutMs(500)
            .build();

    assertNull(checker.checkNow());
    assertEquals(1, checker.getFailedCheckCount());
  }

  @Test
  void periodicCheck_firesOnSchedule() throws Exception {
    String endpoint = startVersionServer("{\"version\":\"2.0.0\"}", 200);
    checker =
        UpdateChecker.builder()
            .endpoint(endpoint)
            .currentVersion("1.0.0")
            .checkIntervalMs(100)
            .build();

    CountDownLatch latch = new CountDownLatch(1);
    checker.addListener(v -> latch.countDown());
    checker.start();

    assertTrue(latch.await(5, TimeUnit.SECONDS), "scheduled check should fire");
    assertTrue(checker.getCheckCount() >= 1);
  }

  @Test
  void builder_rejectsMissingEndpoint() {
    assertThrows(IllegalStateException.class, () -> UpdateChecker.builder().build());
  }

  @Test
  void listenerException_doesNotBreakCheck() throws Exception {
    String endpoint = startVersionServer("{\"version\":\"5.0.0\"}", 200);
    checker = UpdateChecker.builder().endpoint(endpoint).currentVersion("1.0.0").build();
    checker.addListener(
        v -> {
          throw new RuntimeException("boom");
        });

    VersionInfo result = checker.checkNow();
    assertNotNull(result, "listener failure must not fail the check");
  }

  @Test
  void removeListener_stopsNotification() throws Exception {
    String endpoint = startVersionServer("{\"version\":\"5.0.0\"}", 200);
    checker = UpdateChecker.builder().endpoint(endpoint).currentVersion("1.0.0").build();
    AtomicReference<VersionInfo> notified = new AtomicReference<>();
    java.util.function.Consumer<VersionInfo> listener = notified::set;
    checker.addListener(listener);
    checker.removeListener(listener);
    checker.checkNow();
    assertNull(notified.get(), "removed listener must not be notified");
  }

  // --- AgentUpdater ---

  @Test
  void stageUpdate_downloadAndVerify() throws Exception {
    byte[] payload = "fake-agent-jar-bytes".getBytes(StandardCharsets.UTF_8);
    String sha256 = ReleaseInfo.sha256(payload);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/agent.jar",
        exchange -> {
          exchange.sendResponseHeaders(200, payload.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
          }
          exchange.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/agent.jar";

    File staging = Files.createTempDirectory("weaver-update-test").toFile();
    staging.deleteOnExit();
    AgentUpdater updater = new AgentUpdater(staging);
    VersionInfo version =
        VersionInfo.builder().version("2.0.0").downloadUrl(url).sha256(sha256).build();

    AgentUpdater.UpdateResult result = updater.stageUpdate(version, Collections.emptyMap());
    assertTrue(result.isSuccess(), result.getMessage());
    assertEquals(AgentUpdater.UpdateState.READY, result.getState());
    assertTrue(result.getStagedFile().exists());
    assertEquals(AgentUpdater.UpdateState.READY, updater.getState());
    assertEquals("2.0.0", updater.getStagedVersion().getVersion());

    updater.abort();
    assertEquals(AgentUpdater.UpdateState.IDLE, updater.getState());
    assertFalse(result.getStagedFile().exists());
  }

  @Test
  void stageUpdate_checksumMismatch_fails() throws Exception {
    byte[] payload = "tampered-bytes".getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/agent.jar",
        exchange -> {
          exchange.sendResponseHeaders(200, payload.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
          }
          exchange.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/agent.jar";

    File staging = Files.createTempDirectory("weaver-update-test").toFile();
    staging.deleteOnExit();
    AgentUpdater updater = new AgentUpdater(staging);
    VersionInfo version =
        VersionInfo.builder().version("2.0.0").downloadUrl(url).sha256("0".repeat(64)).build();

    AgentUpdater.UpdateResult result = updater.stageUpdate(version, null);
    assertFalse(result.isSuccess());
    assertEquals(AgentUpdater.UpdateState.FAILED, result.getState());
  }

  @Test
  void stageUpdate_noDownloadUrl_checkOnlyMode() throws Exception {
    File staging = Files.createTempDirectory("weaver-update-test").toFile();
    staging.deleteOnExit();
    AgentUpdater updater = new AgentUpdater(staging);
    VersionInfo version = VersionInfo.builder().version("2.0.0").build();

    AgentUpdater.UpdateResult result = updater.stageUpdate(version, null);
    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("check-only"));
  }

  @Test
  void stageUpdate_nullVersion_fails() throws Exception {
    File staging = Files.createTempDirectory("weaver-update-test").toFile();
    staging.deleteOnExit();
    AgentUpdater updater = new AgentUpdater(staging);
    assertFalse(updater.stageUpdate(null, null).isSuccess());
  }
}
