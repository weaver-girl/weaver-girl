package com.github.cc11001100.weavergirl.core.exporter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OtlpHttpExporterTest {

    private SpanData createSpan(String traceId, String spanId, String opName) {
        return SpanData.builder()
                .traceId(traceId)
                .spanId(spanId)
                .operationName(opName)
                .startTimeMs(System.currentTimeMillis())
                .durationMs(100)
                .build();
    }

    @Test
    void export_shouldSendHttpPost() throws Exception {
        // Start a simple HTTP server
        CountDownLatch requestLatch = new CountDownLatch(1);
        AtomicReference<String> receivedBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody()));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            receivedBody.set(body.toString());
            requestLatch.countDown();

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            OtlpHttpExporter exporter = OtlpHttpExporter.builder()
                    .endpoint("http://127.0.0.1:" + port + "/v1/traces")
                    .build();

            List<SpanData> spans = new ArrayList<>();
            spans.add(createSpan("trace1", "span1", "op1"));
            spans.add(createSpan("trace1", "span2", "op2"));

            exporter.export(spans);

            assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
            assertNotNull(receivedBody.get());
            assertTrue(receivedBody.get().startsWith("["));
            assertTrue(receivedBody.get().contains("\"traceId\":\"trace1\""));
            assertEquals(2, exporter.getExportedCount());
            assertEquals(0, exporter.getFailedCount());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void export_shouldSendJsonContentType() throws Exception {
        CountDownLatch requestLatch = new CountDownLatch(1);
        AtomicReference<String> contentType = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestLatch.countDown();

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            OtlpHttpExporter exporter = OtlpHttpExporter.builder()
                    .endpoint("http://127.0.0.1:" + port + "/v1/traces")
                    .build();

            exporter.export(Collections.singletonList(createSpan("t1", "s1", "op1")));

            assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
            assertEquals("application/json", contentType.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void export_failure_shouldIncrementFailedCount() {
        // Use a port that nobody is listening on
        OtlpHttpExporter exporter = OtlpHttpExporter.builder()
                .endpoint("http://127.0.0.1:1/v1/traces")
                .connectTimeout(1000)
                .readTimeout(1000)
                .build();

        List<SpanData> spans = Collections.singletonList(createSpan("t1", "s1", "op1"));
        exporter.export(spans);

        assertEquals(0, exporter.getExportedCount());
        assertTrue(exporter.getFailedCount() > 0, "failedCount should be incremented on connection failure");
    }

    @Test
    void export_shouldHandleNullAndEmpty() {
        OtlpHttpExporter exporter = OtlpHttpExporter.builder()
                .endpoint("http://127.0.0.1:1/v1/traces")
                .build();

        // Should not throw
        exporter.export(null);
        exporter.export(Collections.emptyList());

        assertEquals(0, exporter.getExportedCount());
        assertEquals(0, exporter.getFailedCount());
    }

    @Test
    void builder_shouldRejectMissingEndpoint() {
        assertThrows(IllegalStateException.class, () -> OtlpHttpExporter.builder().build());
    }

    @Test
    void builder_shouldSetCustomHeaders() throws Exception {
        CountDownLatch requestLatch = new CountDownLatch(1);
        AtomicReference<String> authHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestLatch.countDown();

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            OtlpHttpExporter exporter = OtlpHttpExporter.builder()
                    .endpoint("http://127.0.0.1:" + port + "/v1/traces")
                    .header("Authorization", "Bearer test-token")
                    .build();

            exporter.export(Collections.singletonList(createSpan("t1", "s1", "op1")));

            assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
            assertEquals("Bearer test-token", authHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void export_serverError_shouldIncrementFailedCount() throws Exception {
        CountDownLatch requestLatch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            requestLatch.countDown();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            OtlpHttpExporter exporter = OtlpHttpExporter.builder()
                    .endpoint("http://127.0.0.1:" + port + "/v1/traces")
                    .build();

            exporter.export(Collections.singletonList(createSpan("t1", "s1", "op1")));

            assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
            assertEquals(0, exporter.getExportedCount());
            assertEquals(1, exporter.getFailedCount());
        } finally {
            server.stop(0);
        }
    }
}
