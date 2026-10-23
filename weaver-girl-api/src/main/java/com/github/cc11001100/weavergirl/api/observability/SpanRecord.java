package com.github.cc11001100.weavergirl.api.observability;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of a completed span, handed to every {@link SpanSink}.
 *
 * <p>This is the API-level view of a span. It carries what a backend needs to build its own record
 * — trace identity, parent link, timing, status and semantic-convention attributes — without
 * exposing engine types such as the exporter's {@code SpanData}. A product that ships spans to a
 * backend the agent does not build in (Zipkin, Datadog, an internal store) implements {@link
 * SpanSink} and reads this type.
 *
 * <p>{@link #getKind()} is the value of the {@link Tracer#SPAN_KIND_ATTRIBUTE} ({@code server},
 * {@code client}, {@code producer}, {@code consumer}), or {@code internal} when the span does not
 * name one. Like the OTLP exporter, the record keeps the kind out of {@link #getAttributes()} so a
 * consumer does not emit it twice. Baggage is request-scoped propagation state and stays in {@link
 * #getBaggage()}; it is not copied into attributes.
 *
 * @since 1.0.0
 */
public final class SpanRecord {

  /** Kind used for spans that do not declare one. */
  public static final String KIND_INTERNAL = "internal";

  private final String traceId;
  private final String spanId;
  private final String parentSpanId;
  private final String operationName;
  private final String kind;
  private final long startTimeMs;
  private final long durationMs;
  private final String status;
  private final boolean sampled;
  private final Map<String, String> attributes;
  private final Map<String, String> baggage;

  private SpanRecord(Builder builder) {
    this.traceId = builder.traceId;
    this.spanId = builder.spanId;
    this.parentSpanId = builder.parentSpanId;
    this.operationName = builder.operationName;
    this.kind = builder.kind;
    this.startTimeMs = builder.startTimeMs;
    this.durationMs = builder.durationMs;
    this.status = builder.status;
    this.sampled = builder.sampled;
    this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    this.baggage = Collections.unmodifiableMap(new LinkedHashMap<>(builder.baggage));
  }

  /** Convert a completed span. Package-visible so only the dispatcher builds records. */
  static SpanRecord from(SpanContext span, long durationMs) {
    Map<String, String> attributes = new LinkedHashMap<>(span.getAttributes());
    String kind = attributes.remove(Tracer.SPAN_KIND_ATTRIBUTE);
    if (kind == null || kind.trim().isEmpty()) {
      kind = KIND_INTERNAL;
    } else {
      kind = kind.trim().toLowerCase(java.util.Locale.ROOT);
    }
    return new Builder()
        .traceId(span.getTraceId())
        .spanId(span.getSpanId())
        .parentSpanId(span.getParentSpanId())
        .operationName(
            span.getOperationName() == null || span.getOperationName().isEmpty()
                ? "unknown"
                : span.getOperationName())
        .kind(kind)
        .startTimeMs(span.getStartTimeMs())
        .durationMs(Math.max(0, durationMs))
        .status(span.getErrorStatus())
        .sampled(span.isSampled())
        .attributes(attributes)
        .baggage(span.getBaggage())
        .build();
  }

  /** The 32-hex-character W3C trace ID, or a caller-supplied ID. */
  public String getTraceId() {
    return traceId;
  }

  /** The 16-hex-character W3C span ID. */
  public String getSpanId() {
    return spanId;
  }

  /** The parent span ID, or null for a root span. */
  public String getParentSpanId() {
    return parentSpanId;
  }

  /** Logical operation name, for example {@code GET /orders}. Never null. */
  public String getOperationName() {
    return operationName;
  }

  /** {@code server}, {@code client}, {@code producer}, {@code consumer} or {@code internal}. */
  public String getKind() {
    return kind;
  }

  /** Start time in milliseconds since the epoch. */
  public long getStartTimeMs() {
    return startTimeMs;
  }

  /** Duration in milliseconds, never negative. */
  public long getDurationMs() {
    return durationMs;
  }

  /** End time in milliseconds since the epoch. */
  public long getEndTimeMs() {
    return startTimeMs + durationMs;
  }

  /** The status the span ended with ({@code OK}, {@code ERROR}), or null when none was recorded. */
  public String getStatus() {
    return status;
  }

  /** Whether the span was sampled. A sink that only wants sampled spans checks this. */
  public boolean isSampled() {
    return sampled;
  }

  /** Whether the span ended as an error: status {@code ERROR} or attribute {@code error=true}. */
  public boolean isError() {
    return (status != null && "ERROR".equalsIgnoreCase(status))
        || "true".equalsIgnoreCase(attributes.get("error"));
  }

  /** Span attributes; {@code span.kind} is not included. Never null, never writable. */
  public Map<String, String> getAttributes() {
    return attributes;
  }

  /** One span attribute, or null when it was not set. */
  public String getAttribute(String key) {
    return attributes.get(key);
  }

  /** Baggage carried by the span. Never null, never writable. */
  public Map<String, String> getBaggage() {
    return baggage;
  }

  /** Create a builder, mainly for tests and custom emitters. */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String toString() {
    return "SpanRecord{trace="
        + traceId
        + ", span="
        + spanId
        + ", parent="
        + parentSpanId
        + ", op="
        + operationName
        + ", kind="
        + kind
        + ", durationMs="
        + durationMs
        + ", status="
        + status
        + ", attributes="
        + attributes.size()
        + "}";
  }

  /** Builder for {@link SpanRecord}. */
  public static final class Builder {
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String operationName = "unknown";
    private String kind = KIND_INTERNAL;
    private long startTimeMs;
    private long durationMs;
    private String status;
    private boolean sampled = true;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final Map<String, String> baggage = new LinkedHashMap<>();

    public Builder traceId(String traceId) {
      this.traceId = traceId;
      return this;
    }

    public Builder spanId(String spanId) {
      this.spanId = spanId;
      return this;
    }

    public Builder parentSpanId(String parentSpanId) {
      this.parentSpanId = parentSpanId;
      return this;
    }

    public Builder operationName(String operationName) {
      this.operationName = operationName;
      return this;
    }

    public Builder kind(String kind) {
      this.kind = kind;
      return this;
    }

    public Builder startTimeMs(long startTimeMs) {
      this.startTimeMs = startTimeMs;
      return this;
    }

    public Builder durationMs(long durationMs) {
      this.durationMs = durationMs;
      return this;
    }

    public Builder status(String status) {
      this.status = status;
      return this;
    }

    public Builder sampled(boolean sampled) {
      this.sampled = sampled;
      return this;
    }

    public Builder attribute(String key, String value) {
      if (key != null && value != null) {
        this.attributes.put(key, value);
      }
      return this;
    }

    public Builder attributes(Map<String, String> attributes) {
      if (attributes != null) {
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
          attribute(entry.getKey(), entry.getValue());
        }
      }
      return this;
    }

    public Builder baggage(Map<String, String> baggage) {
      if (baggage != null) {
        this.baggage.putAll(baggage);
      }
      return this;
    }

    public SpanRecord build() {
      if (traceId == null || traceId.isEmpty()) {
        throw new IllegalArgumentException("traceId is required");
      }
      if (spanId == null || spanId.isEmpty()) {
        throw new IllegalArgumentException("spanId is required");
      }
      if (kind == null || kind.isEmpty()) {
        kind = KIND_INTERNAL;
      }
      if (operationName == null || operationName.isEmpty()) {
        operationName = "unknown";
      }
      if (durationMs < 0) {
        durationMs = 0;
      }
      return new SpanRecord(this);
    }
  }
}
