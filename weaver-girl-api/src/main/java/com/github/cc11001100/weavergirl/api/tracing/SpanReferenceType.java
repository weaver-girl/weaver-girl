package com.github.cc11001100.weavergirl.api.tracing;

/**
 * Span reference types used in {@link SpanLink} and distributed tracing.
 *
 * <p>These correspond to the W3C Trace Context reference types:
 *
 * <ul>
 *   <li>{@link #CHILD_OF} — the linked span is a child of the current span
 *   <li>{@link #FOLLOWS_FROM} — the linked span follows from the current span (e.g., in a pipeline)
 * </ul>
 *
 * @since 2.0.0
 */
public enum SpanReferenceType {

  /** The linked span is a child of the current span. */
  CHILD_OF,

  /** The linked span follows from the current span. */
  FOLLOWS_FROM;

  /**
   * Parse a reference type from its string name, case-insensitive.
   *
   * @param value the string value, e.g. "CHILD_OF" or "FOLLOWS_FROM"
   * @return the matching enum constant, or {@code null} if not recognized
   */
  public static SpanReferenceType fromString(String value) {
    if (value == null) return null;
    switch (value.trim().toUpperCase()) {
      case "CHILD_OF":
        return CHILD_OF;
      case "FOLLOWS_FROM":
        return FOLLOWS_FROM;
      default:
        return null;
    }
  }
}
