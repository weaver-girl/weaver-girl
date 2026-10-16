package com.github.cc11001100.weavergirl.core.alert;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for DefaultAlertEngine, AlertDeduplicator, LoggingAlertChannel. */
class AlertEngineTest {

  private DefaultAlertEngine engine;
  private CopyOnWriteArrayList<AlertEvent> capturedAlerts;

  @BeforeEach
  void setUp() {
    engine = new DefaultAlertEngine();
    capturedAlerts = new CopyOnWriteArrayList<>();
    engine.addChannel(capturedAlerts::add);
  }

  @Test
  @DisplayName("evaluate: rule triggered should fire alert")
  void evaluate_ruleTriggered_shouldFireAlert() {
    AlertRule rule =
        AlertRule.builder()
            .name("high-cpu")
            .metric("cpu_usage")
            .operator("gt")
            .threshold(100)
            .build();
    engine.addRule(rule);

    List<AlertEvent> result = engine.evaluate("cpu_usage", 150);

    assertEquals(1, result.size());
    assertEquals(1, capturedAlerts.size());
    assertEquals("high-cpu", capturedAlerts.get(0).getRuleName());
    assertEquals(150.0, capturedAlerts.get(0).getActualValue(), 0.001);
    assertEquals(100.0, capturedAlerts.get(0).getThreshold(), 0.001);
  }

  @Test
  @DisplayName("evaluate: rule not triggered should not fire alert")
  void evaluate_ruleNotTriggered_shouldNotFireAlert() {
    AlertRule rule =
        AlertRule.builder()
            .name("high-cpu")
            .metric("cpu_usage")
            .operator("gt")
            .threshold(100)
            .build();
    engine.addRule(rule);

    List<AlertEvent> result = engine.evaluate("cpu_usage", 50);

    assertTrue(result.isEmpty());
    assertTrue(capturedAlerts.isEmpty());
  }

  @Test
  @DisplayName("evaluate: disabled rule should not fire alert")
  void evaluate_disabledRule_shouldNotFireAlert() {
    AlertRule rule =
        AlertRule.builder()
            .name("high-cpu")
            .metric("cpu_usage")
            .operator("gt")
            .threshold(100)
            .build();
    rule.setEnabled(false);
    engine.addRule(rule);

    List<AlertEvent> result = engine.evaluate("cpu_usage", 150);

    assertTrue(result.isEmpty());
    assertTrue(capturedAlerts.isEmpty());
  }

  @Test
  @DisplayName("evaluate: multiple rules should fire matching only")
  void evaluate_multipleRules_shouldFireMatchingOnly() {
    AlertRule gtRule =
        AlertRule.builder()
            .name("too-high")
            .metric("cpu_usage")
            .operator("gt")
            .threshold(100)
            .build();
    AlertRule ltRule =
        AlertRule.builder()
            .name("too-low")
            .metric("cpu_usage")
            .operator("lt")
            .threshold(10)
            .build();
    engine.addRule(gtRule);
    engine.addRule(ltRule);

    List<AlertEvent> result = engine.evaluate("cpu_usage", 150);

    assertEquals(1, result.size());
    assertEquals("too-high", result.get(0).getRuleName());
    assertEquals(1, capturedAlerts.size());
    assertEquals("too-high", capturedAlerts.get(0).getRuleName());
  }

  @Test
  @DisplayName("deduplication: same rule within window should suppress")
  void deduplication_sameRuleWithinWindow_shouldSuppress() {
    AlertRule rule =
        AlertRule.builder()
            .name("high-cpu")
            .metric("cpu_usage")
            .operator("gt")
            .threshold(100)
            .build();
    engine.addRule(rule);

    // First evaluation: should fire
    List<AlertEvent> first = engine.evaluate("cpu_usage", 150);
    assertEquals(1, first.size());

    // Second evaluation immediately: should be suppressed
    List<AlertEvent> second = engine.evaluate("cpu_usage", 150);
    assertTrue(second.isEmpty());

    // Channel should have been called only once
    assertEquals(1, capturedAlerts.size());
  }

  @Test
  @DisplayName("history: should record fired alerts")
  void history_shouldRecordFiredAlerts() {
    AlertRule rule1 =
        AlertRule.builder().name("rule-1").metric("m1").operator("gt").threshold(50).build();
    AlertRule rule2 =
        AlertRule.builder().name("rule-2").metric("m2").operator("gt").threshold(50).build();
    engine.addRule(rule1);
    engine.addRule(rule2);

    engine.evaluate("m1", 100);
    // Clear dedup so second alert can fire
    engine.setDedupWindowMs(0);
    engine.evaluate("m2", 100);

    List<AlertEvent> history = engine.getHistory();
    assertEquals(2, history.size());
  }

  @Test
  @DisplayName("loggingChannel: should not throw")
  void loggingChannel_shouldNotThrow() {
    LoggingAlertChannel channel = new LoggingAlertChannel();
    AlertEvent event =
        new AlertEvent(
            System.currentTimeMillis(), "test-rule", "warning", "test message", 42.0, 100.0);

    assertDoesNotThrow(() -> channel.onAlert(event));
    assertDoesNotThrow(() -> channel.onAlert(null));
  }

  @Test
  @DisplayName("removeRule: should stop evaluation")
  void removeRule_shouldStopEvaluation() {
    AlertRule rule =
        AlertRule.builder()
            .name("high-cpu")
            .metric("cpu_usage")
            .operator("gt")
            .threshold(100)
            .build();
    engine.addRule(rule);

    // Verify it fires before removal
    engine.evaluate("cpu_usage", 150);
    assertEquals(1, capturedAlerts.size());

    // Remove and verify it stops firing
    engine.removeRule("high-cpu");
    capturedAlerts.clear();

    List<AlertEvent> result = engine.evaluate("cpu_usage", 150);
    assertTrue(result.isEmpty());
    assertTrue(capturedAlerts.isEmpty());
  }

  /** A channel that throws must not stop the others, and removing one stops it being called. */
  @Test
  void aChannelThatThrowsDoesNotStopTheOthers() {
    com.github.cc11001100.weavergirl.api.alert.AlertChannel broken =
        event -> {
          throw new IllegalStateException("channel down");
        };
    engine.addChannel(null);
    engine.addChannel(broken);

    engine.addRule(rule("high-cpu", "cpu_usage", null));
    assertEquals(1, engine.evaluate("cpu_usage", 150).size());
    assertEquals(1, capturedAlerts.size(), "the healthy channel was skipped");

    engine.removeChannel(broken);
    capturedAlerts.clear();
    engine.setDedupWindowMs(0);
    engine.evaluate("cpu_usage", 150);
    assertEquals(1, capturedAlerts.size(), "removing the broken channel dropped the healthy one");
  }

  /** With no message of its own, the alert describes the rule that fired. */
  @Test
  void anAlertWithoutAMessageDescribesTheRule() {
    engine.addRule(rule("high-cpu", "cpu_usage", null));

    AlertEvent event = engine.evaluate("cpu_usage", 150).get(0);

    assertEquals("cpu_usage gt 100.0", event.getMessage());
  }

  /** Once the history is full the oldest entry makes room for the newest. */
  @Test
  void historyDropsTheOldestEntryOnceItIsFull() {
    engine.setDedupWindowMs(0);
    for (int i = 0; i < 1001; i++) {
      engine.addRule(rule("rule-" + i, "metric-" + i, "fired"));
    }
    for (int i = 0; i < 1001; i++) {
      engine.evaluate("metric-" + i, 150);
    }

    List<AlertEvent> history = engine.getHistory();
    assertEquals(1000, history.size());
    assertEquals("rule-1", history.get(0).getRuleName(), "the oldest alert was kept");
    assertEquals("rule-1000", history.get(history.size() - 1).getRuleName());
  }

  /** Clearing forgets the rules, so a value that used to alert no longer does. */
  @Test
  void clearForgetsRulesAndHistory() {
    engine.addRule(rule("high-cpu", "cpu_usage", "busy"));
    engine.evaluate("cpu_usage", 150);
    assertFalse(engine.getHistory().isEmpty());

    engine.clear();

    assertTrue(engine.getRules().isEmpty());
    assertTrue(engine.getHistory().isEmpty());
    assertTrue(engine.evaluate("cpu_usage", 150).isEmpty());
  }

  /** Null names and null rules are ignored rather than stored. */
  @Test
  void nullRulesAndNamesAreIgnored() {
    engine.addRule(null);
    engine.removeRule(null);
    assertTrue(engine.getRules().isEmpty());
  }

  private static AlertRule rule(String name, String metric, String message) {
    return AlertRule.builder()
        .name(name)
        .metric(metric)
        .operator("gt")
        .threshold(100)
        .message(message)
        .build();
  }

}
