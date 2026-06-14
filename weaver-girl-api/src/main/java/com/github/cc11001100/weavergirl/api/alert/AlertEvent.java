package com.github.cc11001100.weavergirl.api.alert;

/**
 * An alert that has been triggered by a rule.
 *
 * @since 1.2.0
 */
public class AlertEvent {

    private final long timestamp;
    private final String ruleName;
    private final String severity;
    private final String message;
    private final double actualValue;
    private final double threshold;

    public AlertEvent(long timestamp, String ruleName, String severity,
                      String message, double actualValue, double threshold) {
        this.timestamp = timestamp;
        this.ruleName = ruleName;
        this.severity = severity;
        this.message = message;
        this.actualValue = actualValue;
        this.threshold = threshold;
    }

    public long getTimestamp() { return timestamp; }
    public String getRuleName() { return ruleName; }
    public String getSeverity() { return severity; }
    public String getMessage() { return message; }
    public double getActualValue() { return actualValue; }
    public double getThreshold() { return threshold; }

    @Override
    public String toString() {
        return "Alert[" + severity + "] " + ruleName + ": " + message
                + " (value=" + actualValue + ", threshold=" + threshold + ")";
    }
}
