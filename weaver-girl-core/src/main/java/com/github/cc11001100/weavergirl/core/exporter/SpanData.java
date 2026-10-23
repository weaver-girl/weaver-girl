package com.github.cc11001100.weavergirl.core.exporter;

import com.github.cc11001100.weavergirl.api.tracing.SpanLink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable trace span data for export. */
public class SpanData {

  private final String traceId;
  private final String spanId;
  private final String parentSpanId;
  private final String operationName;
  private final long startTimeMs;
  private final long durationMs;
  private final Map<String, String> attributes;
  private final String status;
  private final int kind;
  private final List<SpanLink> links;

  private SpanData(Builder builder) {
    this.traceId = builder.traceId;
    this.spanId = builder.spanId;
    this.parentSpanId = builder.parentSpanId;
    this.operationName = builder.operationName;
    this.startTimeMs = builder.startTimeMs;
    this.durationMs = builder.durationMs;
    this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    this.status = builder.status;
    this.kind = builder.kind;
    this.links = Collections.unmodifiableList(new ArrayList<>(builder.links));
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public String getParentSpanId() {
    return parentSpanId;
  }

  public String getOperationName() {
    return operationName;
  }

  public long getStartTimeMs() {
    return startTimeMs;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  public String getStatus() {
    return status;
  }

  /** OTLP span kind: 1 internal, 2 server, 3 client, 4 producer, 5 consumer. */
  public int getKind() {
    return kind;
  }

  public List<SpanLink> getLinks() {
    return links;
  }

  /**
   * Convert to an OTLP JSON span object.
   *
   * <p>Only the span itself is produced; {@link OtlpHttpExporter} wraps one or more of these in the
   * {@code resourceSpans}/{@code scopeSpans} envelope required by the OTLP/HTTP JSON receiver.
   */
  public String toOtlpJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"traceId\":\"").append(escapeJson(traceId)).append("\"");
    sb.append(",\"spanId\":\"").append(escapeJson(spanId)).append("\"");
    if (parentSpanId != null) {
      sb.append(",\"parentSpanId\":\"").append(escapeJson(parentSpanId)).append("\"");
    }
    sb.append(",\"name\":\"").append(escapeJson(operationName)).append("\"");
    // OTLP span kind; 1 = SPAN_KIND_INTERNAL. Interposed method spans default to internal, while
    // server/client plugins declare themselves through the "span.kind" span attribute.
    sb.append(",\"kind\":").append(kind);
    sb.append(",\"startTimeUnixNano\":\"").append(startTimeMs * 1_000_000).append("\"");
    sb.append(",\"endTimeUnixNano\":\"")
        .append((startTimeMs + durationMs) * 1_000_000)
        .append("\"");
    sb.append(",\"status\":{\"code\":\"").append(statusCode(status)).append("\"}");
    if (!attributes.isEmpty()) {
      sb.append(",\"attributes\":[");
      boolean first = true;
      for (Map.Entry<String, String> entry : attributes.entrySet()) {
        if (!first) sb.append(",");
        sb.append("{\"key\":\"")
            .append(escapeJson(entry.getKey()))
            .append("\",\"value\":{\"stringValue\":\"")
            .append(escapeJson(entry.getValue()))
            .append("\"}}");
        first = false;
      }
      sb.append("]");
    }
    if (!links.isEmpty()) {
      sb.append(",\"links\":[");
      boolean firstLink = true;
      for (SpanLink link : links) {
        if (!firstLink) sb.append(",");
        sb.append("{\"traceId\":\"").append(escapeJson(link.getTraceId())).append("\"");
        sb.append(",\"spanId\":\"").append(escapeJson(link.getSpanId())).append("\"");
        if (!link.getAttributes().isEmpty()) {
          sb.append(",\"attributes\":[");
          boolean firstAttr = true;
          for (Map.Entry<String, String> attrEntry : link.getAttributes().entrySet()) {
            if (!firstAttr) sb.append(",");
            sb.append("{\"key\":\"")
                .append(escapeJson(attrEntry.getKey()))
                .append("\",\"value\":{\"stringValue\":\"")
                .append(escapeJson(attrEntry.getValue()))
                .append("\"}}");
            firstAttr = false;
          }
          sb.append("]");
        }
        sb.append("}");
        firstLink = false;
      }
      sb.append("]");
    }
    sb.append("}");
    return sb.toString();
  }

  /**
   * Map the internal status word to the OTLP enum name.
   *
   * <p>OTLP JSON uses protobuf enum names, so {@code "OK"} would be rejected; the collector expects
   * {@code STATUS_CODE_OK} / {@code STATUS_CODE_ERROR} / {@code STATUS_CODE_UNSET}.
   */
  static String statusCode(String status) {
    if ("ERROR".equalsIgnoreCase(status)) {
      return "STATUS_CODE_ERROR";
    }
    if ("OK".equalsIgnoreCase(status)) {
      return "STATUS_CODE_OK";
    }
    return "STATUS_CODE_UNSET";
  }

  /** Minimal JSON string escaping for the values this class writes. */
  static String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
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
        case '\t':
          out.append("\\t");
          break;
        default:
          out.append(c);
          break;
      }
    }
    return out.toString();
  }

  @Override
  public String toString() {
    return String.format(
        "SpanData{trace=%s, span=%s, op=%s, duration=%dms}",
        traceId, spanId, operationName, durationMs);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String operationName;
    private long startTimeMs;
    private long durationMs;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private String status = "OK";
    private int kind = 1;
    private final List<SpanLink> links = new ArrayList<>();

    public Builder traceId(String v) {
      this.traceId = v;
      return this;
    }

    public Builder spanId(String v) {
      this.spanId = v;
      return this;
    }

    public Builder parentSpanId(String v) {
      this.parentSpanId = v;
      return this;
    }

    public Builder operationName(String v) {
      this.operationName = v;
      return this;
    }

    public Builder startTimeMs(long v) {
      this.startTimeMs = v;
      return this;
    }

    public Builder durationMs(long v) {
      this.durationMs = v;
      return this;
    }

    public Builder status(String v) {
      this.status = v;
      return this;
    }

    public Builder kind(int v) {
      this.kind = v;
      return this;
    }

    public Builder attribute(String key, String value) {
      attributes.put(key, value);
      return this;
    }

    public Builder links(List<SpanLink> v) {
      this.links.clear();
      this.links.addAll(v);
      return this;
    }

    public Builder link(SpanLink link) {
      if (link != null) {
        this.links.add(link);
      }
      return this;
    }

    public SpanData build() {
      return new SpanData(this);
    }
  }
}
