package com.github.cc11001100.weavergirl.api.tracing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a link to another span, used for batch/async operations.
 *
 * @since 1.1.0
 */
public class SpanLink {

  private final String traceId;
  private final String spanId;
  private final Map<String, String> attributes;
  private final SpanReferenceType referenceType;

  private SpanLink(Builder builder) {
    this.traceId = builder.traceId;
    this.spanId = builder.spanId;
    this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    this.referenceType = builder.referenceType;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  /**
   * Reference type for this link, e.g. {@link SpanReferenceType#CHILD_OF} or {@link
   * SpanReferenceType#FOLLOWS_FROM}.
   *
   * @since 2.0.0
   */
  public SpanReferenceType getReferenceType() {
    return referenceType;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String traceId;
    private String spanId;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private SpanReferenceType referenceType = SpanReferenceType.CHILD_OF;

    public Builder traceId(String traceId) {
      this.traceId = traceId;
      return this;
    }

    public Builder spanId(String spanId) {
      this.spanId = spanId;
      return this;
    }

    public Builder referenceType(SpanReferenceType referenceType) {
      this.referenceType = referenceType;
      return this;
    }

    public Builder attribute(String key, String value) {
      attributes.put(key, value);
      return this;
    }

    public Builder attributes(Map<String, String> attrs) {
      attributes.putAll(attrs);
      return this;
    }

    public SpanLink build() {
      if (traceId == null || spanId == null) {
        throw new IllegalArgumentException("traceId and spanId are required");
      }
      return new SpanLink(this);
    }
  }

  @Override
  public String toString() {
    return "SpanLink{trace="
        + traceId
        + ", span="
        + spanId
        + ", ref="
        + referenceType
        + ", attrs="
        + attributes.size()
        + "}";
  }
}
