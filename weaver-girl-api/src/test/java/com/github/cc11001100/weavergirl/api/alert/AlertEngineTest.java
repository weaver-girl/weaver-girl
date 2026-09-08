package com.github.cc11001100.weavergirl.api.alert;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

/** Tests for alerting engine (P57). */
class AlertEngineTest {

  @AfterEach
  void tearDown() {
    AlertEngine.clear();
  }

  // ===== AlertRule =====

  @Test
  void rule_evaluate_gt() {
    AlertRule rule =
        AlertRule.builder()
            .name("test")
            .metric("duration_ms")
            .operator("gt")
            .threshold(1000)
            .build();
    assertTrue(rule.evaluate(1500));
    assertFalse(rule.evaluate(500));
    assertFalse(rule.evaluate(1000));
  }

  @Test
  void rule_evaluate_gte() {
    AlertRule rule =
        AlertRule.builder().name("test").metric("x").operator("gte").threshold(100).build();
    assertTrue(rule.evaluate(100));
    assertTrue(rule.evaluate(200));
    assertFalse(rule.evaluate(50));
  }

  @Test
  void rule_evaluate_lt() {
    AlertRule rule =
        AlertRule.builder().name("test").metric("x").operator("lt").threshold(10).build();
    assertTrue(rule.evaluate(5));
    assertFalse(rule.evaluate(10));
  }

  @Test
  void rule_evaluate_eq() {
    AlertRule rule =
        AlertRule.builder().name("test").metric("x").operator("eq").threshold(42).build();
    assertTrue(rule.evaluate(42));
    assertFalse(rule.evaluate(43));
  }

  @Test
  void rule_disabledDoesNotFire() {
    AlertRule rule =
        AlertRule.builder()
            .name("test")
            .metric("x")
            .operator("gt")
            .threshold(0)
            .enabled(false)
            .build();
    assertFalse(rule.evaluate(999));
  }

  @Test
  void rule_enableDisable() {
    AlertRule rule =
        AlertRule.builder().name("test").metric("x").operator("gt").threshold(0).build();
    assertTrue(rule.isEnabled());
    rule.setEnabled(false);
    assertFalse(rule.isEnabled());
  }

  @Test
  void rule_rejectsMissingName() {
    assertThrows(IllegalArgumentException.class, () -> AlertRule.builder().metric("x").build());
  }

  @Test
  void rule_rejectsMissingMetric() {
    assertThrows(IllegalArgumentException.class, () -> AlertRule.builder().name("n").build());
  }

  @Test
  void rule_toString() {
    AlertRule rule =
        AlertRule.builder().name("slow").metric("dur").operator("gt").threshold(1000).build();
    assertTrue(rule.toString().contains("slow"));
  }

  // ===== AlertEngine =====

  @Test
  void engine_evaluate_triggersAlert() {
    AlertEngine.addRule(
        AlertRule.builder()
            .name("slow-call")
            .metric("duration_ms")
            .operator("gt")
            .threshold(1000)
            .severity("warning")
            .message("Slow call detected")
            .build());

    List<AlertEvent> alerts = AlertEngine.evaluate("duration_ms", 2500);
    assertEquals(1, alerts.size());
    assertEquals("slow-call", alerts.get(0).getRuleName());
    assertEquals("warning", alerts.get(0).getSeverity());
    assertEquals(2500, alerts.get(0).getActualValue());
  }

  @Test
  void engine_evaluate_noTrigger() {
    AlertEngine.addRule(
        AlertRule.builder()
            .name("high-err")
            .metric("error_rate")
            .operator("gt")
            .threshold(0.5)
            .build());

    List<AlertEvent> alerts = AlertEngine.evaluate("error_rate", 0.1);
    assertTrue(alerts.isEmpty());
  }

  @Test
  void engine_evaluate_differentMetric() {
    AlertEngine.addRule(
        AlertRule.builder().name("r1").metric("a").operator("gt").threshold(10).build());

    // Evaluating wrong metric should not trigger
    assertTrue(AlertEngine.evaluate("b", 999).isEmpty());
  }

  @Test
  void engine_notifiesChannels() {
    CopyOnWriteArrayList<AlertEvent> received = new CopyOnWriteArrayList<>();
    AlertEngine.addChannel(received::add);

    AlertEngine.addRule(
        AlertRule.builder()
            .name("ch-test")
            .metric("m")
            .operator("gt")
            .threshold(0)
            .message("test msg")
            .build());

    AlertEngine.evaluate("m", 1);
    assertEquals(1, received.size());
    assertEquals("test msg", received.get(0).getMessage());
  }

  @Test
  void engine_historyTracksAlerts() {
    AlertEngine.addRule(
        AlertRule.builder().name("h1").metric("m").operator("gt").threshold(0).build());

    AlertEngine.evaluate("m", 1);
    AlertEngine.evaluate("m", 2);
    assertEquals(2, AlertEngine.getHistory().size());
  }

  @Test
  void engine_multipleRules() {
    AlertEngine.addRule(
        AlertRule.builder().name("r1").metric("cpu").operator("gt").threshold(80).build());
    AlertEngine.addRule(
        AlertRule.builder()
            .name("r2")
            .metric("cpu")
            .operator("gt")
            .threshold(90)
            .severity("critical")
            .build());

    List<AlertEvent> alerts = AlertEngine.evaluate("cpu", 95);
    assertEquals(2, alerts.size());
  }

  @Test
  void engine_removeRule() {
    AlertEngine.addRule(
        AlertRule.builder().name("to-remove").metric("m").operator("gt").threshold(0).build());
    AlertEngine.removeRule("to-remove");
    assertTrue(AlertEngine.evaluate("m", 999).isEmpty());
  }

  @Test
  void engine_clear() {
    AlertEngine.addRule(
        AlertRule.builder().name("r").metric("m").operator("gt").threshold(0).build());
    AlertEngine.evaluate("m", 1);
    AlertEngine.clear();
    assertTrue(AlertEngine.getRules().isEmpty());
    assertTrue(AlertEngine.getHistory().isEmpty());
  }

  // ===== AlertEvent =====

  @Test
  void alertEvent_toString() {
    AlertEvent event = new AlertEvent(1000, "test-rule", "warning", "msg", 42.0, 10.0);
    String str = event.toString();
    assertTrue(str.contains("warning"));
    assertTrue(str.contains("test-rule"));
  }

  // ===== AlertChannel =====

  @Test
  void alertChannel_functionalInterface() {
    AlertChannel channel = alert -> {};
    assertNotNull(channel);
  }
}
