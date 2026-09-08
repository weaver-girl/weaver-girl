package com.github.cc11001100.weavergirl.api.alert;

/**
 * An alert rule that evaluates conditions and fires alerts.
 *
 * <h3>Built-in rule types:</h3>
 *
 * <ul>
 *   <li>{@code slow_call} — fires when duration exceeds threshold
 *   <li>{@code high_error_rate} — fires when error rate exceeds threshold
 *   <li>{@code circuit_open} — fires when circuit breaker opens
 * </ul>
 *
 * @since 1.2.0
 */
public class AlertRule {

  private final String name;
  private final String type;
  private final String metric;
  private final String operator;
  private final double threshold;
  private final long windowMs;
  private final String severity;
  private final String message;
  private volatile boolean enabled;

  private AlertRule(Builder builder) {
    this.name = builder.name;
    this.type = builder.type;
    this.metric = builder.metric;
    this.operator = builder.operator;
    this.threshold = builder.threshold;
    this.windowMs = builder.windowMs;
    this.severity = builder.severity;
    this.message = builder.message;
    this.enabled = builder.enabled;
  }

  /** Rule name (unique identifier). */
  public String getName() {
    return name;
  }

  /** Rule type (e.g. "slow_call", "high_error_rate", "circuit_open"). */
  public String getType() {
    return type;
  }

  /** Metric to evaluate (e.g. "duration_ms", "error_rate", "circuit_state"). */
  public String getMetric() {
    return metric;
  }

  /** Comparison operator ("gt", "gte", "lt", "lte", "eq"). */
  public String getOperator() {
    return operator;
  }

  /** Threshold value. */
  public double getThreshold() {
    return threshold;
  }

  /** Evaluation window in milliseconds. */
  public long getWindowMs() {
    return windowMs;
  }

  /** Alert severity ("info", "warning", "critical"). */
  public String getSeverity() {
    return severity;
  }

  /** Human-readable alert message template. */
  public String getMessage() {
    return message;
  }

  /** Whether this rule is active. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Enable or disable this rule. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Evaluate if a value triggers this rule.
   *
   * @param value the metric value to check
   * @return true if the alert should fire
   */
  public boolean evaluate(double value) {
    if (!enabled) return false;
    switch (operator) {
      case "gt":
        return value > threshold;
      case "gte":
        return value >= threshold;
      case "lt":
        return value < threshold;
      case "lte":
        return value <= threshold;
      case "eq":
        return value == threshold;
      default:
        return false;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String name;
    private String type = "custom";
    private String metric;
    private String operator = "gt";
    private double threshold;
    private long windowMs = 60000;
    private String severity = "warning";
    private String message;
    private boolean enabled = true;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder type(String type) {
      this.type = type;
      return this;
    }

    public Builder metric(String metric) {
      this.metric = metric;
      return this;
    }

    public Builder operator(String op) {
      this.operator = op;
      return this;
    }

    public Builder threshold(double t) {
      this.threshold = t;
      return this;
    }

    public Builder windowMs(long ms) {
      this.windowMs = ms;
      return this;
    }

    public Builder severity(String s) {
      this.severity = s;
      return this;
    }

    public Builder message(String m) {
      this.message = m;
      return this;
    }

    public Builder enabled(boolean e) {
      this.enabled = e;
      return this;
    }

    public AlertRule build() {
      if (name == null || name.isEmpty()) throw new IllegalArgumentException("name required");
      if (metric == null) throw new IllegalArgumentException("metric required");
      return new AlertRule(this);
    }
  }

  @Override
  public String toString() {
    return "AlertRule{name='"
        + name
        + "', type="
        + type
        + ", "
        + metric
        + " "
        + operator
        + " "
        + threshold
        + "}";
  }
}
