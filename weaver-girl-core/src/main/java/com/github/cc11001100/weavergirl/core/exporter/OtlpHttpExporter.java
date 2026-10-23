package com.github.cc11001100.weavergirl.core.exporter;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports spans to an OpenTelemetry Collector via OTLP/HTTP JSON.
 *
 * <p>Posts a complete {@code ExportTraceServiceRequest} ({@code resourceSpans → scopeSpans →
 * spans}), which is what a collector's {@code /v1/traces} receiver expects. Resource attributes
 * identify the process: {@code service.name} defaults to the {@code weavergirl.service.name} system
 * property, or {@code weaver-girl}; the builder adds {@code service.version}, the deployment
 * environment and anything else the caller supplies. Supports custom headers (e.g. authentication),
 * connect/read timeouts, and tracks export statistics.
 *
 * @since 1.1.0
 */
public class OtlpHttpExporter implements SpanFormatter {

  private static final Logger log = LoggerFactory.getLogger(OtlpHttpExporter.class);

  private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
  private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

  private static final String INSTRUMENTATION_SCOPE = "weaver-girl-agent";

  private final String endpoint;
  private final Map<String, String> headers;
  private final Map<String, String> resourceAttributes;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  private final AtomicLong exportedCount = new AtomicLong();
  private final AtomicLong failedCount = new AtomicLong();

  private OtlpHttpExporter(Builder builder) {
    this.endpoint = builder.endpoint;
    this.headers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    Map<String, String> resource = defaultResourceAttributes();
    resource.putAll(builder.resourceAttributes);
    this.resourceAttributes = java.util.Collections.unmodifiableMap(resource);
    this.connectTimeoutMs = builder.connectTimeoutMs;
    this.readTimeoutMs = builder.readTimeoutMs;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public void export(List<SpanData> spans) {
    if (spans == null || spans.isEmpty()) {
      return;
    }

    String payload = toExportRequest(spans, resourceAttributes);

    // Send via HTTP POST
    try {
      URL url = new URL(endpoint);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setConnectTimeout(connectTimeoutMs);
      conn.setReadTimeout(readTimeoutMs);

      // Add custom headers
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        conn.setRequestProperty(entry.getKey(), entry.getValue());
      }

      byte[] body = payload.getBytes(StandardCharsets.UTF_8);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
        os.flush();
      }

      int responseCode = conn.getResponseCode();
      if (responseCode >= 200 && responseCode < 300) {
        exportedCount.addAndGet(spans.size());
        log.debug("[OTLP] Exported {} spans to {}", spans.size(), endpoint);
      } else {
        failedCount.incrementAndGet();
        log.warn("[OTLP] Export failed with status {} for {} spans", responseCode, spans.size());
      }
      conn.disconnect();
    } catch (Exception e) {
      failedCount.incrementAndGet();
      log.warn("[OTLP] Export error: {}", e.getMessage());
    }
  }

  /**
   * Render an OTLP/HTTP {@code ExportTraceServiceRequest} carrying every span.
   *
   * <p>Package-visible so the encoding can be asserted without a server: a collector rejects the
   * whole batch if the envelope is wrong, which used to happen when the payload was a bare JSON
   * array of spans.
   */
  static String toExportRequest(List<SpanData> spans, Map<String, String> resourceAttributes) {
    StringBuilder json = new StringBuilder();
    json.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[");
    boolean firstResource = true;
    for (Map.Entry<String, String> entry : resourceAttributes.entrySet()) {
      if (!firstResource) json.append(",");
      json.append("{\"key\":\"")
          .append(SpanData.escapeJson(entry.getKey()))
          .append("\",\"value\":{\"stringValue\":\"")
          .append(SpanData.escapeJson(entry.getValue()))
          .append("\"}}");
      firstResource = false;
    }
    json.append("]},\"scopeSpans\":[{\"scope\":{\"name\":\"")
        .append(INSTRUMENTATION_SCOPE)
        .append("\"},\"spans\":[");
    for (int i = 0; i < spans.size(); i++) {
      if (i > 0) {
        json.append(",");
      }
      json.append(spans.get(i).toOtlpJson());
    }
    json.append("]}]}]}");
    return json.toString();
  }

  private static Map<String, String> defaultResourceAttributes() {
    Map<String, String> resource = new LinkedHashMap<String, String>();
    String serviceName = System.getProperty("weavergirl.service.name");
    resource.put(
        "service.name",
        serviceName != null && !serviceName.trim().isEmpty() ? serviceName.trim() : "weaver-girl");
    resource.put("service.version", packageVersion());
    resource.put("telemetry.sdk.name", "weaver-girl");
    resource.put("telemetry.sdk.language", "java");
    return resource;
  }

  private static String packageVersion() {
    String version = OtlpHttpExporter.class.getPackage().getImplementationVersion();
    return version != null && !version.isEmpty() ? version : "unknown";
  }

  public Map<String, String> getResourceAttributes() {
    return resourceAttributes;
  }

  public long getExportedCount() {
    return exportedCount.get();
  }

  public long getFailedCount() {
    return failedCount.get();
  }

  public String getEndpoint() {
    return endpoint;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  /** Builder for constructing {@link OtlpHttpExporter} instances. */
  public static class Builder {

    private String endpoint;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> resourceAttributes = new LinkedHashMap<>();
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

    /**
     * Set the OTLP HTTP endpoint URL (required).
     *
     * @param endpoint the full URL to the OTLP endpoint
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Add a custom HTTP header.
     *
     * @param key header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String key, String value) {
      this.headers.put(key, value);
      return this;
    }

    /**
     * Set an OTLP resource attribute, such as {@code service.name}, {@code service.version} or
     * {@code deployment.environment}. Values here override the built-in defaults.
     *
     * @param key attribute name
     * @param value attribute value
     * @return this builder
     */
    public Builder resourceAttribute(String key, String value) {
      if (key != null && value != null) {
        this.resourceAttributes.put(key, value);
      }
      return this;
    }

    /**
     * Set the connect timeout in milliseconds.
     *
     * @param connectTimeoutMs timeout in ms
     * @return this builder
     */
    public Builder connectTimeout(int connectTimeoutMs) {
      this.connectTimeoutMs = connectTimeoutMs;
      return this;
    }

    /**
     * Set the read timeout in milliseconds.
     *
     * @param readTimeoutMs timeout in ms
     * @return this builder
     */
    public Builder readTimeout(int readTimeoutMs) {
      this.readTimeoutMs = readTimeoutMs;
      return this;
    }

    /**
     * Build the exporter.
     *
     * @return a new {@link OtlpHttpExporter}
     * @throws IllegalStateException if endpoint is not set
     */
    public OtlpHttpExporter build() {
      if (endpoint == null || endpoint.isEmpty()) {
        throw new IllegalStateException("Endpoint must be set");
      }
      return new OtlpHttpExporter(this);
    }
  }
}
