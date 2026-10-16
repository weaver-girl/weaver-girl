package com.github.cc11001100.weavergirl.core.graceful;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.testsupport.Statics;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GracefulDegradationManagerTest {

  @AfterEach
  void tearDown() throws Exception {
    GracefulDegradationManager.resetForTest();
    Field enabledField = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
    enabledField.setAccessible(true);
    enabledField.setBoolean(null, true);
    Field toggleCountField = GlobalInterceptionSwitch.class.getDeclaredField("toggleCount");
    toggleCountField.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicLong) toggleCountField.get(null)).set(0);
    com.github.cc11001100.weavergirl.core.sampling.SamplingController controller =
        com.github.cc11001100.weavergirl.core.sampling.SamplingController.getInstance();
    controller.setSamplingRate(1);
    controller.setMaxRate(100);
    controller.resetCounter();
  }

  @Test
  void enterEmergencyMode_disablesInterception() {
    assertTrue(GlobalInterceptionSwitch.isEnabled());
    GracefulDegradationManager.enterEmergencyMode("test");
    assertTrue(GracefulDegradationManager.isEmergencyMode());
    assertFalse(GlobalInterceptionSwitch.isEnabled());
  }

  @Test
  void enterEmergencyMode_isIdempotent() {
    GracefulDegradationManager.enterEmergencyMode("test");
    GracefulDegradationManager.enterEmergencyMode("again");
    assertFalse(GlobalInterceptionSwitch.isEnabled());
  }

  @Test
  void updateSamplingPressure_highLoad_reducesRate() {
    GracefulDegradationManager.updateSamplingPressure(0.9);
    assertEquals(0.9, GracefulDegradationManager.getLastLoadFactor(), 0.001);
  }

  @Test
  void updateSamplingPressure_highLoad_increasesSamplingRate() {
    com.github.cc11001100.weavergirl.core.sampling.SamplingController controller =
        com.github.cc11001100.weavergirl.core.sampling.SamplingController.getInstance();
    controller.setMaxRate(100);
    controller.setSamplingRate(2);
    GracefulDegradationManager.resetForTest();

    GracefulDegradationManager.updateSamplingPressure(0.9);

    assertEquals(50, controller.getSamplingRate());
  }

  @Test
  void updateSamplingPressure_lowLoad_doesNotChangeRate() {
    com.github.cc11001100.weavergirl.core.sampling.SamplingController controller =
        com.github.cc11001100.weavergirl.core.sampling.SamplingController.getInstance();
    controller.setMaxRate(100);
    controller.setSamplingRate(10);
    GracefulDegradationManager.resetForTest();

    GracefulDegradationManager.updateSamplingPressure(0.0);

    assertEquals(10, controller.getSamplingRate());
  }

  @Test
  void updateSamplingPressure_doesNothingInEmergencyMode() {
    com.github.cc11001100.weavergirl.core.sampling.SamplingController controller =
        com.github.cc11001100.weavergirl.core.sampling.SamplingController.getInstance();
    controller.setMaxRate(100);
    GracefulDegradationManager.resetForTest();
    GracefulDegradationManager.enterEmergencyMode("test");

    assertTrue(GracefulDegradationManager.isEmergencyMode());
    assertEquals(100, controller.getSamplingRate());
    GracefulDegradationManager.updateSamplingPressure(0.9);
    assertEquals(100, controller.getSamplingRate());
  }

  @Test
  void notifyExportBackendUnavailable_repeatedFailures_eventuallyEntersEmergency() {
    for (int i = 0; i < 199; i++) {
      GracefulDegradationManager.notifyExportBackendUnavailable(i + 1);
      assertFalse(GracefulDegradationManager.isEmergencyMode());
    }
    GracefulDegradationManager.notifyExportBackendUnavailable(200);
    assertTrue(GracefulDegradationManager.isEmergencyMode());
  }

  /** Once emergency mode is on, further export failures change nothing. */
  @Test
  void exportFailuresAreIgnoredOnceEmergencyModeIsOn() {
    GracefulDegradationManager.enterEmergencyMode("test");

    GracefulDegradationManager.notifyExportBackendUnavailable(10);

    assertTrue(GracefulDegradationManager.isEmergencyMode());
    assertEquals(0.0, GracefulDegradationManager.getLastLoadFactor(), 0.001);
  }

  /** A run of export failures that is not a multiple of ten only counts, it does not react. */
  @Test
  void anExportFailureOffTheBoundaryOnlyCounts() {
    GracefulDegradationManager.notifyExportBackendUnavailable(7);

    assertFalse(GracefulDegradationManager.isEmergencyMode());
    assertEquals(0.0, GracefulDegradationManager.getLastLoadFactor(), 0.001);
  }

  /** High load that the sampling rate already covers does not raise it again. */
  @Test
  void highLoadDoesNotRaiseARateThatAlreadyCoversIt() {
    SamplingController controller = SamplingController.getInstance();
    controller.setMaxRate(100);
    controller.setSamplingRate(80);

    GracefulDegradationManager.updateSamplingPressure(0.9);

    assertEquals(80, controller.getSamplingRate());
  }

  /**
   * A sampling controller that cannot be capped, and an event listener that throws, are both logged and
   * survived. Entering emergency mode still disables interception.
   */
  @Test
  void aBrokenSamplerAndABrokenListenerDoNotBlockEmergencyMode() throws Exception {
    Field rate = SamplingController.class.getDeclaredField("samplingRate");
    rate.setAccessible(true);
    SamplingController controller = SamplingController.getInstance();
    Object previous = Statics.read(rate, controller);
    Statics.write(rate, controller, null);

    AtomicInteger published = new AtomicInteger();
    InterceptorEventListener broken =
        event -> {
          published.incrementAndGet();
          throw new IllegalStateException("listener down");
        };
    InterceptorEventPublisher.getInstance().addListener(broken);
    try {
      GracefulDegradationManager.enterEmergencyMode("test");

      assertTrue(GracefulDegradationManager.isEmergencyMode());
      assertFalse(GlobalInterceptionSwitch.isEnabled());
      assertTrue(published.get() > 0, "the lifecycle event was never published");
    } finally {
      Statics.write(rate, controller, previous);
      InterceptorEventPublisher.getInstance().removeListener(broken);
    }
  }

  /**
   * Publishing a lifecycle event only survives an {@code Exception}. An {@code Error} escapes it, and
   * emergency mode and the export warning both have to absorb that themselves.
   */
  /**
   * Publishing a lifecycle event only survives an {@code Exception}. An {@code Error} escapes it, and
   * emergency mode and the export warning both have to absorb that themselves.
   */
  @Test
  void anEventThatCannotBePublishedDoesNotStopDegradation() throws Exception {
    Field current = Tracer.class.getDeclaredField("CURRENT_SPAN");
    Object real = Statics.read(current, null);
    Statics.write(current, null, new ThreadLocalThatFails());
    try {
      assertDoesNotThrow(() -> GracefulDegradationManager.enterEmergencyMode("test"));
      GracefulDegradationManager.resetForTest();

      assertDoesNotThrow(() -> GracefulDegradationManager.notifyExportBackendUnavailable(10));

      assertFalse(GracefulDegradationManager.isEmergencyMode());
      assertEquals(0.9, GracefulDegradationManager.getLastLoadFactor(), 0.001);
    } finally {
      Statics.write(current, null, real);
    }
  }

  /** A span holder whose read fails outright, so stamping an event never completes. */
  private static final class ThreadLocalThatFails extends ThreadLocal<Object> {
    @Override
    public Object get() {
      throw new OutOfMemoryError("span unavailable");
    }
  }
}
