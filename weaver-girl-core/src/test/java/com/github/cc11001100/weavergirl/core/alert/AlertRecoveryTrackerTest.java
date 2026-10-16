package com.github.cc11001100.weavergirl.core.alert;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertRecoveryTrackerTest {

  private AlertRecoveryTracker tracker;
  private List<AlertEvent> recoveredEvents;

  @BeforeEach
  void setUp() {
    tracker = new AlertRecoveryTracker();
    recoveredEvents = new ArrayList<>();
  }

  @Test
  void update_alertFired_marksActive() {
    AlertRule rule = AlertRule.builder().name("high-cpu").metric("cpu").threshold(80).build();
    tracker.updateAndCheckRecovery(rule, 90, true, Collections.emptyList());
    assertTrue(tracker.isActive("high-cpu"));
  }

  @Test
  void update_alertRecovered_notifiesChannel() {
    AlertRule rule = AlertRule.builder().name("high-cpu").metric("cpu").threshold(80).build();
    List<AlertChannel> channels = new ArrayList<>();
    channels.add(recoveredEvents::add);
    // First: alert fires
    tracker.updateAndCheckRecovery(rule, 90, true, channels);
    // Second: alert recovers
    boolean recovered = tracker.updateAndCheckRecovery(rule, 70, false, channels);
    assertTrue(recovered);
    assertEquals(1, recoveredEvents.size());
    assertTrue(recoveredEvents.get(0).getMessage().startsWith("RECOVERED"));
    assertFalse(tracker.isActive("high-cpu"));
  }

  @Test
  void update_noPriorAlert_noRecovery() {
    AlertRule rule = AlertRule.builder().name("high-cpu").metric("cpu").threshold(80).build();
    boolean recovered = tracker.updateAndCheckRecovery(rule, 70, false, Collections.emptyList());
    assertFalse(recovered);
  }

  /**
   * A recovery whose rule never set a message describes itself from the metric, and a channel that throws
   * while it is told does not stop the recovery from being reported.
   */
  @Test
  void aRecoveryWithoutAMessageDescribesTheMetric() {
    AlertRule rule =
        AlertRule.builder().name("high-cpu").metric("cpu").operator(">").threshold(80).build();
    List<AlertChannel> channels = new ArrayList<>();
    channels.add(
        event -> {
          throw new IllegalStateException("channel down");
        });
    tracker.updateAndCheckRecovery(rule, 90, true, channels);

    boolean recovered = tracker.updateAndCheckRecovery(rule, 70, false, channels);

    assertTrue(recovered, "a channel throwing stopped the recovery from being reported");
    assertFalse(tracker.isActive("high-cpu"));
  }

  /** A recovery whose rule carries its own message repeats that message rather than describing the metric. */
  @Test
  void aRecoveryKeepsTheMessageTheRuleDeclared() {
    AlertRule rule =
        AlertRule.builder()
            .name("high-cpu")
            .metric("cpu")
            .threshold(80)
            .message("cpu cooled down")
            .build();
    List<AlertChannel> channels = new ArrayList<>();
    channels.add(recoveredEvents::add);
    tracker.updateAndCheckRecovery(rule, 90, true, channels);

    assertTrue(tracker.updateAndCheckRecovery(rule, 70, false, channels));
    assertTrue(
        recoveredEvents.get(0).getMessage().contains("cpu cooled down"),
        "the recovery replaced the rule's own message: " + recoveredEvents.get(0).getMessage());
  }

  @Test
  void clear_removesAllTracking() {
    AlertRule rule = AlertRule.builder().name("high-cpu").metric("cpu").threshold(80).build();
    tracker.updateAndCheckRecovery(rule, 90, true, Collections.emptyList());
    tracker.clear();
    assertFalse(tracker.isActive("high-cpu"));
  }
}
