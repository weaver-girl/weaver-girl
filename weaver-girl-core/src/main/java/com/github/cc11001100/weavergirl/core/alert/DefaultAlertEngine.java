package com.github.cc11001100.weavergirl.core.alert;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Instance-based core implementation of the alert engine.
 *
 * <p>Evaluates metric values against registered rules and dispatches
 * triggered alerts to all registered channels, with deduplication
 * support to suppress repeated alerts within a configurable window.</p>
 *
 * @since 1.2.0
 */
public class DefaultAlertEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultAlertEngine.class);
    private static final int MAX_HISTORY = 1000;

    private final ConcurrentHashMap<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AlertChannel> channels = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<AlertEvent> history = new LinkedBlockingQueue<>(MAX_HISTORY);
    private final AlertDeduplicator deduplicator;

    /**
     * Create a DefaultAlertEngine with the default 1-minute deduplication window.
     */
    public DefaultAlertEngine() {
        this(60000);
    }

    /**
     * Create a DefaultAlertEngine with a custom deduplication window.
     *
     * @param dedupWindowMs deduplication window in milliseconds
     */
    public DefaultAlertEngine(long dedupWindowMs) {
        this.deduplicator = new AlertDeduplicator(dedupWindowMs);
    }

    // ---- Rule management ----

    /**
     * Add an alert rule.
     *
     * @param rule the rule to add
     */
    public void addRule(AlertRule rule) {
        if (rule != null && rule.getName() != null) {
            rules.put(rule.getName(), rule);
        }
    }

    /**
     * Remove an alert rule by name.
     *
     * @param name the rule name to remove
     */
    public void removeRule(String name) {
        if (name != null) {
            rules.remove(name);
        }
    }

    /**
     * Get all registered rules.
     *
     * @return unmodifiable collection of rules
     */
    public Collection<AlertRule> getRules() {
        return Collections.unmodifiableCollection(new ArrayList<>(rules.values()));
    }

    // ---- Channel management ----

    /**
     * Add an alert notification channel.
     *
     * @param channel the channel to add
     */
    public void addChannel(AlertChannel channel) {
        if (channel != null) {
            channels.add(channel);
        }
    }

    /**
     * Remove an alert notification channel.
     *
     * @param channel the channel to remove
     */
    public void removeChannel(AlertChannel channel) {
        channels.remove(channel);
    }

    // ---- Evaluation ----

    /**
     * Evaluate a metric value against all rules for the given metric name.
     *
     * @param metricName the metric name to evaluate
     * @param value      the current metric value
     * @return list of triggered alert events (may be empty)
     */
    public List<AlertEvent> evaluate(String metricName, double value) {
        List<AlertEvent> triggered = new ArrayList<>();
        for (AlertRule rule : rules.values()) {
            if (!rule.isEnabled()) {
                continue;
            }
            if (!metricName.equals(rule.getMetric())) {
                continue;
            }
            if (!rule.evaluate(value)) {
                continue;
            }
            // Deduplication check
            if (deduplicator.shouldSuppress(rule.getName())) {
                log.debug("Suppressed duplicate alert for rule {}", rule.getName());
                continue;
            }
            deduplicator.recordFire(rule.getName());
            AlertEvent event = new AlertEvent(
                    System.currentTimeMillis(),
                    rule.getName(),
                    rule.getSeverity(),
                    rule.getMessage() != null ? rule.getMessage()
                            : rule.getMetric() + " " + rule.getOperator() + " " + rule.getThreshold(),
                    value,
                    rule.getThreshold()
            );
            triggered.add(event);
            addToHistory(event);
            notifyChannels(event);
        }
        return triggered;
    }

    // ---- History ----

    /**
     * Get alert history.
     *
     * @return list of past alert events
     */
    public List<AlertEvent> getHistory() {
        List<AlertEvent> snapshot = new ArrayList<>();
        history.drainTo(snapshot);
        // Put them back so history is not consumed by reading
        for (AlertEvent event : snapshot) {
            history.offer(event);
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Clear all rules, channels, history, and deduplication state.
     */
    public void clear() {
        rules.clear();
        channels.clear();
        history.clear();
        deduplicator.clear();
    }

    // ---- Deduplication configuration ----

    /**
     * Set the deduplication window.
     *
     * @param ms window in milliseconds
     */
    public void setDedupWindowMs(long ms) {
        deduplicator.setDedupWindowMs(ms);
    }

    // ---- Internal ----

    private void addToHistory(AlertEvent event) {
        // Offer to bounded queue; if full, poll oldest then offer
        if (!history.offer(event)) {
            history.poll();
            history.offer(event);
        }
    }

    private void notifyChannels(AlertEvent event) {
        for (AlertChannel channel : channels) {
            try {
                channel.onAlert(event);
            } catch (Exception e) {
                log.warn("Channel {} threw exception: {}", channel.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
