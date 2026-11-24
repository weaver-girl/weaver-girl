package com.github.cc11001100.weavergirl.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.cc11001100.weavergirl.api.config.PerformanceSwitches;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** {@code -Dweavergirl.mdc.enabled=false} must stop the bridge entirely, not just hide it. */
class MdcSwitchTest {

  @AfterEach
  void reset() {
    PerformanceSwitches.setEnabled(PerformanceSwitches.MDC_BRIDGE, true);
    MDC.clear();
    ThreadContext.clear();
  }

  @Test
  void bridgeOffMeansNoMdcWritesAndReportsUnavailable() {
    PerformanceSwitches.setEnabled(PerformanceSwitches.MDC_BRIDGE, false);
    ThreadContext.put("traceId", "should-not-appear");

    assertFalse(MdcInjector.isMdcAvailable(), "the bridge is off, so it must report unavailable");

    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();
    assertNull(MDC.get("traceId"), "a disabled bridge wrote into MDC");
    // The shared EMPTY_SNAPSHOT is what a disabled bridge returns only when MDC is unreadable;
    // here the cached probe is still consulted, so just prove nothing was written and restore is
    // safe.
    MdcInjector.restore(snapshot);
    assertNull(MDC.get("traceId"));
  }

  @Test
  void bridgeBackOnBridgesAgain() {
    ThreadContext.put("traceId", "restored");

    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();
    assertEquals("restored", MDC.get("traceId"), "the bridge should write again once re-enabled");
    MdcInjector.restore(snapshot);
    assertNull(MDC.get("traceId"));
  }
}
