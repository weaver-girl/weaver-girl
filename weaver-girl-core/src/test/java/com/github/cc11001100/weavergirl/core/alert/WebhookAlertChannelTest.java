package com.github.cc11001100.weavergirl.core.alert;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookAlertChannelTest {

  private HttpServer server;
  private String receivedBody;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/webhook",
        exchange -> {
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(exchange.getRequestBody()));
          receivedBody = reader.lines().collect(Collectors.joining("\n"));
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write("ok".getBytes());
          }
        });
    server.setExecutor(null);
    server.start();
    port = server.getAddress().getPort();
    receivedBody = null;
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  private AlertEvent sampleEvent() {
    return new AlertEvent(
        System.currentTimeMillis(), "high-cpu", "critical", "CPU usage exceeded 80%", 92.5, 80.0);
  }

  @Test
  void onAlert_sendsPostToWebhook() {
    WebhookAlertChannel channel = new WebhookAlertChannel("http://127.0.0.1:" + port + "/webhook");
    channel.onAlert(sampleEvent());
    // Give the HTTP request time to complete
    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }

    assertNotNull(receivedBody);
    assertTrue(receivedBody.contains("high-cpu"));
    assertTrue(receivedBody.contains("critical"));
    assertTrue(receivedBody.contains("92.5"));
  }

  @Test
  void onAlert_nullEvent_doesNotThrow() {
    WebhookAlertChannel channel = new WebhookAlertChannel("http://127.0.0.1:" + port + "/webhook");
    assertDoesNotThrow(() -> channel.onAlert(null));
  }

  @Test
  void onAlert_invalidUrl_logsWarningDoesNotThrow() {
    WebhookAlertChannel channel =
        new WebhookAlertChannel("http://invalid-host-that-does-not-exist:99999/hook", 100, 100);
    assertDoesNotThrow(() -> channel.onAlert(sampleEvent()));
  }
}
