package com.github.cc11001100.weavergirl.core.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
    server.createContext(
        "/v1/traces",
        exchange -> {
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(exchange.getRequestBody()));
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
      OtlpHttpExporter exporter =
          OtlpHttpExporter.builder().endpoint("http://127.0.0.1:" + port + "/v1/traces").build();

      List<SpanData> spans = new ArrayList<>();
      spans.add(createSpan("trace1", "span1", "op1"));
      spans.add(createSpan("trace1", "span2", "op2"));

      exporter.export(spans);

      assertTrue(
          requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
      assertNotNull(receivedBody.get());
      // A collector only accepts the ExportTraceServiceRequest envelope, not a bare span array.
      assertTrue(receivedBody.get().startsWith("{\"resourceSpans\":["), receivedBody.get());
      assertTrue(receivedBody.get().contains("\"resource\":{\"attributes\":["));
      assertTrue(receivedBody.get().contains("\"service.name\""));
      assertTrue(receivedBody.get().contains("\"scopeSpans\":["));
      assertTrue(receivedBody.get().contains("\"scope\":{\"name\":\"weaver-girl-agent\"}"));
      assertTrue(receivedBody.get().contains("\"traceId\":\"trace1\""));
      assertTrue(receivedBody.get().contains("\"spanId\":\"span2\""));
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
    server.createContext(
        "/v1/traces",
        exchange -> {
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          requestLatch.countDown();

          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    try {
      int port = server.getAddress().getPort();
      OtlpHttpExporter exporter =
          OtlpHttpExporter.builder().endpoint("http://127.0.0.1:" + port + "/v1/traces").build();

      exporter.export(Collections.singletonList(createSpan("t1", "s1", "op1")));

      assertTrue(
          requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
      assertEquals("application/json", contentType.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void export_failure_shouldIncrementFailedCount() {
    // Use a port that nobody is listening on
    OtlpHttpExporter exporter =
        OtlpHttpExporter.builder()
            .endpoint("http://127.0.0.1:1/v1/traces")
            .connectTimeout(1000)
            .readTimeout(1000)
            .build();

    List<SpanData> spans = Collections.singletonList(createSpan("t1", "s1", "op1"));
    exporter.export(spans);

    assertEquals(0, exporter.getExportedCount());
    assertTrue(
        exporter.getFailedCount() > 0, "failedCount should be incremented on connection failure");
  }

  @Test
  void export_shouldHandleNullAndEmpty() {
    OtlpHttpExporter exporter =
        OtlpHttpExporter.builder().endpoint("http://127.0.0.1:1/v1/traces").build();

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
    server.createContext(
        "/v1/traces",
        exchange -> {
          authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          requestLatch.countDown();

          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    try {
      int port = server.getAddress().getPort();
      OtlpHttpExporter exporter =
          OtlpHttpExporter.builder()
              .endpoint("http://127.0.0.1:" + port + "/v1/traces")
              .header("Authorization", "Bearer test-token")
              .build();

      exporter.export(Collections.singletonList(createSpan("t1", "s1", "op1")));

      assertTrue(
          requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
      assertEquals("Bearer test-token", authHeader.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void export_serverError_shouldIncrementFailedCount() throws Exception {
    CountDownLatch requestLatch = new CountDownLatch(1);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/traces",
        exchange -> {
          requestLatch.countDown();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();

    try {
      int port = server.getAddress().getPort();
      OtlpHttpExporter exporter =
          OtlpHttpExporter.builder().endpoint("http://127.0.0.1:" + port + "/v1/traces").build();

      exporter.export(Collections.singletonList(createSpan("t1", "s1", "op1")));

      assertTrue(
          requestLatch.await(5, TimeUnit.SECONDS), "Server should receive request within timeout");
      assertEquals(0, exporter.getExportedCount());
      assertEquals(1, exporter.getFailedCount());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void toExportRequest_wrapsEverySpanInTheResourceAndScopeEnvelope() {
    java.util.Map<String, String> resource = new java.util.LinkedHashMap<>();
    resource.put("service.name", "orders");
    resource.put("service.version", "2.1.0");

    String json =
        OtlpHttpExporter.toExportRequest(
            java.util.Arrays.asList(createSpan("t1", "s1", "op1"), createSpan("t1", "s2", "op2")),
            resource);

    assertTrue(json.startsWith("{\"resourceSpans\":[{\"resource\":{\"attributes\":["), json);
    assertTrue(
        json.contains("{\"key\":\"service.name\",\"value\":{\"stringValue\":\"orders\"}}"), json);
    assertTrue(
        json.contains("{\"key\":\"service.version\",\"value\":{\"stringValue\":\"2.1.0\"}}"), json);
    assertTrue(json.contains("\"scopeSpans\":[{\"scope\":{\"name\":\"weaver-girl-agent\"}"));
    assertEquals(2, countOccurrences(json, "\"traceId\":\"t1\""));
    // Envelope closes exactly once, after both spans.
    assertTrue(json.endsWith("]}]}]}"), json);
  }

  @Test
  void builder_resourceAttributesOverrideTheDefaults() {
    OtlpHttpExporter exporter =
        OtlpHttpExporter.builder()
            .endpoint("http://127.0.0.1:1/v1/traces")
            .resourceAttribute("service.name", "payments")
            .resourceAttribute("deployment.environment", "prod")
            .build();

    assertEquals("payments", exporter.getResourceAttributes().get("service.name"));
    assertEquals("prod", exporter.getResourceAttributes().get("deployment.environment"));
    // Defaults the caller did not touch are still present.
    assertEquals("weaver-girl", exporter.getResourceAttributes().get("telemetry.sdk.name"));
    assertEquals("java", exporter.getResourceAttributes().get("telemetry.sdk.language"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> exporter.getResourceAttributes().put("x", "y"));
  }

  @Test
  void builder_ignoresNullResourceAttributes() {
    OtlpHttpExporter exporter =
        OtlpHttpExporter.builder()
            .endpoint("http://127.0.0.1:1/v1/traces")
            .resourceAttribute(null, "v")
            .resourceAttribute("k", null)
            .build();

    assertFalse(exporter.getResourceAttributes().containsKey("k"));
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

  /** The settings a caller configured are the settings the exporter reports. */
  @Test
  void theConfiguredSettingsAreReportedBack() {
    OtlpHttpExporter exporter =
        OtlpHttpExporter.builder()
            .endpoint("http://127.0.0.1:1/v1/traces")
            .header("Authorization", "Bearer test-token")
            .connectTimeout(1500)
            .readTimeout(2500)
            .build();

    assertEquals("http://127.0.0.1:1/v1/traces", exporter.getEndpoint());
    assertEquals("Bearer test-token", exporter.getHeaders().get("Authorization"));
    assertEquals(1500, exporter.getConnectTimeoutMs());
    assertEquals(2500, exporter.getReadTimeoutMs());
  }
}
