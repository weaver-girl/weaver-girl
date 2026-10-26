package com.github.cc11001100.weavergirl.core.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The monitor thread adapts the rate and a second start does nothing. */
class SamplingMonitorTest {

  @AfterEach
  void reset() {
    SamplingController.getInstance().setSamplingRate(1);
    SamplingStrategyRegistry.clear();
  }

  @Test
  void startAdaptsTheRateAndASecondStartIsIgnored() throws Exception {
    SamplingController controller = SamplingController.getInstance();
    controller.setThresholdInvocationsPerSecond(1);
    controller.setMaxRate(100);
    SamplingMonitor monitor = new SamplingMonitor(controller);
    monitor.setCheckIntervalSeconds(1);
    long before = controller.getInvocationCount();

    monitor.start();
    monitor.start();
    try {
      long deadline = System.currentTimeMillis() + 4000;
      while (controller.getSamplingRate() == 1 && System.currentTimeMillis() < deadline) {
        controller.shouldSample();
        Thread.sleep(50);
      }
      assertTrue(controller.getInvocationCount() > before);
      assertTrue(controller.getSamplingRate() > 1);
    } finally {
      monitor.stop();
      monitor.stop();
    }

    int settled = controller.getSamplingRate();
    Thread.sleep(1200);
    assertEquals(settled, controller.getSamplingRate());
  }
}
