package com.github.cc11001100.weavergirl.core.graceful;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import java.lang.reflect.Field;
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
}
