package com.github.cc11001100.weavergirl.core.alert;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alert channel that sends alerts via HTTP POST to a webhook URL as JSON.
 *
 * @since 1.2.0
 */
public class WebhookAlertChannel implements AlertChannel {

  private static final Logger log = LoggerFactory.getLogger(WebhookAlertChannel.class);

  private final String url;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  /**
   * Create a webhook channel with default timeouts (5s connect, 10s read).
   *
   * @param url the webhook endpoint URL
   */
  public WebhookAlertChannel(String url) {
    this(url, 5000, 10000);
  }

  /**
   * Create a webhook channel with custom timeouts.
   *
   * @param url the webhook endpoint URL
   * @param connectTimeoutMs connection timeout in milliseconds
   * @param readTimeoutMs read timeout in milliseconds
   */
  public WebhookAlertChannel(String url, int connectTimeoutMs, int readTimeoutMs) {
    this.url = url;
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  @Override
  public void onAlert(AlertEvent alert) {
    if (alert == null) {
      return;
    }
    try {
      String json = buildJson(alert);
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setConnectTimeout(connectTimeoutMs);
      conn.setReadTimeout(readTimeoutMs);
      conn.setDoOutput(true);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(json.getBytes(StandardCharsets.UTF_8));
      }
      int responseCode = conn.getResponseCode();
      if (responseCode >= 400) {
        log.warn("Webhook returned HTTP {} for alert {}", responseCode, alert.getRuleName());
      }
      conn.disconnect();
    } catch (Exception e) {
      log.warn("Failed to send alert to webhook {}: {}", url, e.getMessage());
    }
  }

  private String buildJson(AlertEvent alert) {
    return "{\"ruleName\":\""
        + escape(alert.getRuleName())
        + "\","
        + "\"severity\":\""
        + escape(alert.getSeverity())
        + "\","
        + "\"message\":\""
        + escape(alert.getMessage())
        + "\","
        + "\"actualValue\":"
        + alert.getActualValue()
        + ","
        + "\"threshold\":"
        + alert.getThreshold()
        + ","
        + "\"timestamp\":"
        + alert.getTimestamp()
        + "}";
  }

  private String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
