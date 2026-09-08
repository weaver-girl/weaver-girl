package com.github.cc11001100.weavergirl.api.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A security audit record for tracking sensitive operations.
 *
 * @since 1.1.0
 */
public class AuditRecord {

  private final long timestamp;
  private final String operation;
  private final String principal;
  private final String target;
  private final String result;
  private final Map<String, String> details;

  public AuditRecord(
      long timestamp,
      String operation,
      String principal,
      String target,
      String result,
      Map<String, String> details) {
    this.timestamp = timestamp;
    this.operation = operation;
    this.principal = principal;
    this.target = target;
    this.result = result;
    this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  /** When the audit event occurred (epoch millis). */
  public long getTimestamp() {
    return timestamp;
  }

  /** The operation type (e.g. "INTERCEPT", "CONFIG_CHANGE", "PLUGIN_OPERATION"). */
  public String getOperation() {
    return operation;
  }

  /** Who performed the operation (e.g. tenant ID, system). */
  public String getPrincipal() {
    return principal;
  }

  /** What was operated on (e.g. class name, config key, plugin name). */
  public String getTarget() {
    return target;
  }

  /** Result of the operation (e.g. "ALLOWED", "DENIED", "SUCCESS", "FAILED"). */
  public String getResult() {
    return result;
  }

  /** Additional details. */
  public Map<String, String> getDetails() {
    return details;
  }

  @Override
  public String toString() {
    return "AuditRecord{" + timestamp + " " + operation + " " + target + " → " + result + "}";
  }

  /** Create a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private long timestamp = System.currentTimeMillis();
    private String operation;
    private String principal;
    private String target;
    private String result;
    private final Map<String, String> details = new LinkedHashMap<>();

    public Builder timestamp(long ts) {
      this.timestamp = ts;
      return this;
    }

    public Builder operation(String op) {
      this.operation = op;
      return this;
    }

    public Builder principal(String p) {
      this.principal = p;
      return this;
    }

    public Builder target(String t) {
      this.target = t;
      return this;
    }

    public Builder result(String r) {
      this.result = r;
      return this;
    }

    public Builder detail(String key, String value) {
      this.details.put(key, value);
      return this;
    }

    public AuditRecord build() {
      return new AuditRecord(timestamp, operation, principal, target, result, details);
    }
  }
}
