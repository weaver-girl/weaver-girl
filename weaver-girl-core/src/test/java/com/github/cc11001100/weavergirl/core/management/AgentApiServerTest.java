package com.github.cc11001100.weavergirl.core.management;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Agent REST API (P62).
 */
class AgentApiServerTest {

    private AgentApiServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        // Find free port
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        server = new AgentApiServer(port);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void statusEndpoint_returnsUp() throws Exception {
        String response = get("/status");
        assertTrue(response.contains("\"status\":\"UP\""));
        assertTrue(response.contains("interceptorCount"));
    }

    @Test
    void pluginsEndpoint_returnsJson() throws Exception {
        String response = get("/plugins");
        assertTrue(response.contains("\"plugins\""));
        assertTrue(response.contains("activeCount"));
    }

    @Test
    void topologyEndpoint_returnsText() throws Exception {
        String response = get("/topology");
        assertTrue(response.contains("Service Topology"));
    }

    @Test
    void alertsEndpoint_returnsJson() throws Exception {
        String response = get("/alerts");
        assertTrue(response.contains("\"alerts\""));
        assertTrue(response.contains("\"count\""));
    }

    @Test
    void metricsEndpoint_returnsJson() throws Exception {
        String response = get("/metrics");
        assertTrue(response.contains("\"metrics\""));
    }

    @Test
    void diagnosticsEndpoint_returnsReport() throws Exception {
        String response = get("/diagnostics");
        assertTrue(response.contains("Diagnostics Report"));
    }

    // ===== Helper =====

    private String get(String path) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        assertEquals(200, conn.getResponseCode());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
