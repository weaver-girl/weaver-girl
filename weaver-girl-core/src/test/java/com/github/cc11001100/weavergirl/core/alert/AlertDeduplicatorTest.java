package com.github.cc11001100.weavergirl.core.alert;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertDeduplicatorTest {

  private AlertDeduplicator deduplicator;

  @BeforeEach
  void setUp() {
    deduplicator = new AlertDeduplicator(1000);
  }

  @Test
  void shouldSuppress_noPreviousFire_returnsFalse() {
    assertFalse(deduplicator.shouldSuppress("rule-1"));
  }

  @Test
  void shouldSuppress_withinWindow_returnsTrue() {
    deduplicator.recordFire("rule-1");
    assertTrue(deduplicator.shouldSuppress("rule-1"));
  }

  @Test
  void shouldSuppress_afterWindowExpires_returnsFalse() throws InterruptedException {
    deduplicator.recordFire("rule-1");
    Thread.sleep(1100);
    assertFalse(deduplicator.shouldSuppress("rule-1"));
  }

  @Test
  void shouldSuppress_differentRulesAreIndependent() {
    deduplicator.recordFire("rule-1");
    assertFalse(deduplicator.shouldSuppress("rule-2"));
  }

  @Test
  void recordFire_resetsWindow() throws InterruptedException {
    deduplicator.recordFire("rule-1");
    assertTrue(deduplicator.shouldSuppress("rule-1"));
    deduplicator.recordFire("rule-1");
    assertTrue(deduplicator.shouldSuppress("rule-1"));
  }

  @Test
  void clear_removesAllState() {
    deduplicator.recordFire("rule-1");
    deduplicator.clear();
    assertFalse(deduplicator.shouldSuppress("rule-1"));
  }

  @Test
  void setDedupWindowMs_updatesWindow() throws InterruptedException {
    deduplicator.setDedupWindowMs(50);
    deduplicator.recordFire("rule-1");
    Thread.sleep(100);
    assertFalse(deduplicator.shouldSuppress("rule-1"));
  }
}
