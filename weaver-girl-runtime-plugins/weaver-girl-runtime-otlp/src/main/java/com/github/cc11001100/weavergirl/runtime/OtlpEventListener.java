package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards interceptor events to an OTLP/HTTP endpoint as log records.
 *
 * <p>The payload is the JSON encoding of OTLP ({@code ExportLogsServiceRequest}), so any collector
 * that accepts {@code application/json} on {@code /v1/logs} can receive it. Posts run on a daemon
 * thread and a failing endpoint never reaches back into the intercepted call.
 */
class OtlpEventListener implements InterceptorEventListener {

  private static final Logger log = LoggerFactory.getLogger(OtlpEventListener.class);

  private final String endpoint;
  private final int timeoutMs;
  private final ExecutorService executor;

  OtlpEventListener(String endpoint, int timeoutMs) {
    this.endpoint = endpoint;
    this.timeoutMs = timeoutMs;
    this.executor =
        Executors.newSingleThreadExecutor(
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "weaver-girl-otlp");
                thread.setDaemon(true);
                return thread;
              }
            });
  }

  @Override
  public void onEvent(InterceptorEvent event) {
    final String body = toRequest(event);
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            post(body);
          }
        });
  }

  /** Stops accepting events and waits briefly for the last post to finish. */
  void shutdown() {
    executor.shutdown();
    try {
      executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void post(String body) {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(endpoint).openConnection();
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(timeoutMs);
      connection.setReadTimeout(timeoutMs);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(bytes.length);
      OutputStream out = connection.getOutputStream();
      try {
        out.write(bytes);
      } finally {
        out.close();
      }
      int status = connection.getResponseCode();
      if (status >= 300) {
        log.warn("OTLP endpoint {} rejected an event with status {}", endpoint, status);
      }
    } catch (Exception e) {
      log.warn("Failed to export event to {}: {}", endpoint, e.getMessage());
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  /** One log record per event, severity derived from the event type. */
  static String toRequest(InterceptorEvent event) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"resourceLogs\":[{\"scopeLogs\":[{\"logRecords\":[{");
    sb.append("\"timeUnixNano\":\"").append(event.getTimestamp() * 1000000L).append("\",");
    sb.append("\"severityText\":\"").append(severity(event.getType())).append("\",");
    sb.append("\"body\":{\"stringValue\":\"").append(escape(body(event))).append("\"},");
    sb.append("\"attributes\":[");
    appendAttribute(sb, "weavergirl.plugin", event.getPlugin());
    sb.append(",");
    appendAttribute(sb, "weavergirl.type", event.getType());
    sb.append(",");
    appendAttribute(sb, "code.namespace", event.getClassName());
    sb.append(",");
    appendAttribute(sb, "code.function", event.getMethodName());
    sb.append(",");
    appendAttribute(sb, "weavergirl.duration_ms", Long.toString(event.getDurationMs()));
    Map<String, String> attributes = event.getAttributes();
    if (attributes != null) {
      for (Map.Entry<String, String> entry : attributes.entrySet()) {
        sb.append(",");
        appendAttribute(sb, "weavergirl." + entry.getKey(), entry.getValue());
      }
    }
    sb.append("]}]}]}]}");
    return sb.toString();
  }

  private static String body(InterceptorEvent event) {
    return event.getPlugin()
        + " "
        + event.getType()
        + " "
        + event.getClassName()
        + "."
        + event.getMethodName()
        + " "
        + event.getDurationMs()
        + "ms";
  }

  private static String severity(String type) {
    return type != null && type.endsWith("-error") ? "ERROR" : "INFO";
  }

  private static void appendAttribute(StringBuilder sb, String key, String value) {
    sb.append("{\"key\":\"")
        .append(escape(key))
        .append("\",\"value\":{\"stringValue\":\"")
        .append(escape(value))
        .append("\"}}");
  }

  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\':
          out.append("\\\\");
          break;
        case '"':
          out.append("\\\"");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        default:
          out.append(c);
          break;
      }
    }
    return out.toString();
  }
}
