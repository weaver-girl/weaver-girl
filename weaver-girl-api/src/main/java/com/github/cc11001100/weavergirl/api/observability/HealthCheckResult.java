package com.github.cc11001100.weavergirl.api.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of one {@link HealthCheck}: a status plus optional diagnostic details.
 *
 * <p>Statuses follow the usual probe vocabulary:
 *
 * <ul>
 *   <li>{@code UP} — the component is healthy;
 *   <li>{@code DEGRADED} — the component works but is impaired (a replica is down, a queue is
 *       filling up);
 *   <li>{@code DOWN} — the component cannot serve;
 *   <li>{@code UNKNOWN} — the check could not decide.
 * </ul>
 *
 * <p>Details are free-form key/value pairs rendered by management endpoints for a human; do not put
 * secrets in them.
 *
 * @since 1.0.0
 */
public final class HealthCheckResult {

  /** Health status of a component. */
  public enum Status {
    UP,
    DEGRADED,
    DOWN,
    UNKNOWN;

    /**
     * Parse a status name, case-insensitively.
     *
     * @param value the status name, or null
     * @return the matching status, or {@link #UNKNOWN} when the value is null or not recognized
     */
    public static Status parse(String value) {
      if (value == null) {
        return UNKNOWN;
      }
      String trimmed = value.trim();
      for (Status status : values()) {
        if (status.name().equalsIgnoreCase(trimmed)) {
          return status;
        }
      }
      return UNKNOWN;
    }
  }

  private static final HealthCheckResult UP =
      new HealthCheckResult(Status.UP, Collections.<String, String>emptyMap());

  private final Status status;
  private final Map<String, String> details;

  private HealthCheckResult(Status status, Map<String, String> details) {
    this.status = status;
    this.details = Collections.unmodifiableMap(new LinkedHashMap<String, String>(details));
  }

  /** A healthy result with no details. */
  public static HealthCheckResult up() {
    return UP;
  }

  /** A healthy result with details. */
  public static HealthCheckResult up(Map<String, String> details) {
    return new HealthCheckResult(Status.UP, detailsOf(details));
  }

  /** An impaired result; {@code reason} is stored as the {@code reason} detail. */
  public static HealthCheckResult degraded(String reason) {
    return of(Status.DEGRADED, reason);
  }

  /** An impaired result with details. */
  public static HealthCheckResult degraded(Map<String, String> details) {
    return new HealthCheckResult(Status.DEGRADED, detailsOf(details));
  }

  /** A failing result; {@code reason} is stored as the {@code reason} detail. */
  public static HealthCheckResult down(String reason) {
    return of(Status.DOWN, reason);
  }

  /** A failing result with details. */
  public static HealthCheckResult down(Map<String, String> details) {
    return new HealthCheckResult(Status.DOWN, detailsOf(details));
  }

  /** A result for a check that could not decide. */
  public static HealthCheckResult unknown(String reason) {
    return of(Status.UNKNOWN, reason);
  }

  /** A result with an explicit status and details. */
  public static HealthCheckResult of(Status status, Map<String, String> details) {
    return new HealthCheckResult(status != null ? status : Status.UNKNOWN, detailsOf(details));
  }

  private static HealthCheckResult of(Status status, String reason) {
    Map<String, String> details = new LinkedHashMap<String, String>();
    if (reason != null && !reason.trim().isEmpty()) {
      details.put("reason", reason);
    }
    return new HealthCheckResult(status, details);
  }

  private static Map<String, String> detailsOf(Map<String, String> details) {
    if (details == null) {
      return Collections.emptyMap();
    }
    Map<String, String> copy = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry : details.entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null) {
        copy.put(entry.getKey(), entry.getValue());
      }
    }
    return copy;
  }

  /** The status. */
  public Status getStatus() {
    return status;
  }

  /** Diagnostic details; never null. */
  public Map<String, String> getDetails() {
    return details;
  }

  /** Whether this result is {@link Status#UP}. */
  public boolean isUp() {
    return status == Status.UP;
  }

  @Override
  public String toString() {
    return "HealthCheckResult{status=" + status + ", details=" + details + "}";
  }
}
