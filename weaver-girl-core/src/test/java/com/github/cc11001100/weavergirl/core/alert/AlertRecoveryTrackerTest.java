package com.github.cc11001100.weavergirl.core.alert;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void clear_removesAllTracking() {
        AlertRule rule = AlertRule.builder().name("high-cpu").metric("cpu").threshold(80).build();
        tracker.updateAndCheckRecovery(rule, 90, true, Collections.emptyList());
        tracker.clear();
        assertFalse(tracker.isActive("high-cpu"));
    }
}
