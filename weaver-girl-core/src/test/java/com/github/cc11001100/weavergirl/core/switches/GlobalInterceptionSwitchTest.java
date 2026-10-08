package com.github.cc11001100.weavergirl.core.switches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GlobalInterceptionSwitchTest {

  // The switch is a JVM-wide singleton other test classes toggle (and only restore the flag,
  // not the counter), so both ends of each test must reset or the count assertions race.
  @BeforeEach
  @AfterEach
  void reset() {
    GlobalInterceptionSwitch.resetForTest();
  }

  @Test
  void isEnabled_trueByDefault() {
    assertTrue(GlobalInterceptionSwitch.isEnabled());
    assertTrue(InterceptorHolder.isInterceptionEnabled());
  }

  @Test
  void setEnabled_falseDisablesAndCountsToggle() {
    InterceptorHolder.setInterceptionEnabled(false, "test");
    assertFalse(GlobalInterceptionSwitch.isEnabled());
    assertEquals(1, GlobalInterceptionSwitch.toggleCount());
  }

  @Test
  void setEnabled_sameValueIsNoOp() {
    InterceptorHolder.setInterceptionEnabled(true, "test");
    assertEquals(0, GlobalInterceptionSwitch.toggleCount());
  }

  @Test
  void setEnabled_toggleBackAndForth() {
    InterceptorHolder.setInterceptionEnabled(false, "test");
    InterceptorHolder.setInterceptionEnabled(true, "test");
    assertTrue(GlobalInterceptionSwitch.isEnabled());
    assertEquals(2, GlobalInterceptionSwitch.toggleCount());
  }
}
