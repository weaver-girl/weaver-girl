package com.github.cc11001100.weavergirl.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for P80 bootstrap wiring: {@link WeaverGirl#initUpdateCheck} creates a
 * periodic version checker only when configured, and
 * {@link WeaverGirl#stopUpdateCheck} tears it down.
 */
class WeaverGirlUpdateCheckTest {

    private WeaverGirl created;
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (created != null) {
            created.stopUpdateCheck();
            created = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void noConfig_createsNothing() {
        created = WeaverGirl.create();
        created.initUpdateCheck(Collections.emptyMap());
        assertNull(created.getUpdateChecker());
    }

    @Test
    void nullConfig_createsNothing() {
        created = WeaverGirl.create();
        created.initUpdateCheck(null);
        assertNull(created.getUpdateChecker());
    }

    @Test
    void endpoint_createsRunningChecker() {
        created = WeaverGirl.create();
        Map<String, String> config = new HashMap<>();
        config.put("updateCheckEndpoint", "http://127.0.0.1:1/version.json");
        config.put("updateCheckIntervalMs", "3600000");
        created.initUpdateCheck(config);

        assertNotNull(created.getUpdateChecker());
        assertTrue(created.getUpdateChecker().isRunning());
        assertEquals("http://127.0.0.1:1/version.json",
                created.getUpdateChecker().getEndpoint());
    }

    @Test
    void invalidInterval_fallsBackToDefault() {
        created = WeaverGirl.create();
        Map<String, String> config = new HashMap<>();
        config.put("updateCheckEndpoint", "http://127.0.0.1:1/version.json");
        config.put("updateCheckIntervalMs", "not-a-number");
        created.initUpdateCheck(config);

        assertNotNull(created.getUpdateChecker());
        assertEquals(24L * 60 * 60 * 1000, created.getUpdateChecker().getCheckIntervalMs());
    }

    @Test
    void checker_detectsNewerVersionEndToEnd() throws Exception {
        byte[] body = "{\"version\":\"9.9.9\",\"releaseNotes\":\"test\"}"
                .getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/version.json", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/version.json";

        created = WeaverGirl.create();
        Map<String, String> config = new HashMap<>();
        config.put("updateCheckEndpoint", endpoint);
        config.put("updateCheckIntervalMs", "100");
        created.initUpdateCheck(config);

        assertNotNull(created.getUpdateChecker());
        // Poll until the scheduled check observes the newer version.
        long deadline = System.currentTimeMillis() + 5000;
        while (created.getUpdateChecker().getLatestAvailable() == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertNotNull(created.getUpdateChecker().getLatestAvailable());
        assertEquals("9.9.9",
                created.getUpdateChecker().getLatestAvailable().getVersion());
        // A warning was logged; the agent itself keeps running.
        assertTrue(created.getUpdateChecker().isRunning());
    }

    @Test
    void stopUpdateCheck_stopsAndClears() {
        created = WeaverGirl.create();
        Map<String, String> config = new HashMap<>();
        config.put("updateCheckEndpoint", "http://127.0.0.1:1/version.json");
        created.initUpdateCheck(config);
        assertNotNull(created.getUpdateChecker());

        created.stopUpdateCheck();
        assertNull(created.getUpdateChecker());
    }

    @Test
    void autoStageListener_rejectsCheckOnlyDescriptor() throws Exception {
        // Direct unit coverage of the listener path is via UpdateCheckerTest;
        // here we verify autoStage wiring does not throw when the descriptor
        // has no download URL (check-only mode logs a warning instead).
        byte[] body = "{\"version\":\"9.9.8\"}".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v";

        created = WeaverGirl.create();
        Map<String, String> config = new HashMap<>();
        config.put("updateCheckEndpoint", endpoint);
        config.put("updateAutoStage", "true");
        created.initUpdateCheck(config);

        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            created.getUpdateChecker().checkNow();
            done.countDown();
        }).start();
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
