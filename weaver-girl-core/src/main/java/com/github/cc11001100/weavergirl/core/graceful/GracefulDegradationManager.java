package com.github.cc11001100.weavergirl.core.graceful;

import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.core.event.LifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates graceful degradation for the agent: emergency disable, sampling pressure, and backend
 * unavailability notices.
 *
 * <p>This class is intentionally small and allocation-free on the hot path. All state is held in
 * {@code volatile} fields or delegated to existing singletons.
 */
public final class GracefulDegradationManager {

  private static final Logger log = LoggerFactory.getLogger(GracefulDegradationManager.class);

  private static volatile boolean emergencyMode = false;
  private static volatile boolean emergencyLogged = false;
  private static volatile double lastLoadFactor = 0.0;
  private static volatile long lastExportFailureNanos = 0L;

  private GracefulDegradationManager() {}

  /** @return true if the agent is in emergency-disabled mode. */
  public static boolean isEmergencyMode() {
    return emergencyMode;
  }

  /**
   * Enter emergency mode: disable interception process-wide and stop sampling-driven work. Safe to
   * call repeatedly; logs only on the first transition.
   *
   * @param reason short description of what triggered emergency mode, e.g. {@code "config"} or
   *     {@code "export-failure"}
   */
  public static void enterEmergencyMode(String reason) {
    if (emergencyMode) {
      return;
    }
    emergencyMode = true;
    log.warn("[GracefulDegradation] Entering emergency mode: reason={}", reason);
    GlobalInterceptionSwitch.setEnabled(false, "graceful-degradation:" + reason);
    try {
      SamplingController.getInstance().setSamplingRate(SamplingController.getInstance().getMaxRate());
    } catch (Exception e) {
      log.debug("[GracefulDegradation] Failed to cap sampling rate: {}", e.getMessage());
    }
    try {
      InterceptorEventPublisher.getInstance()
          .publish(LifecycleEvents.registry(LifecycleEvents.PHASE_EMERGENCY, reason, true, null));
    } catch (Throwable t) {
      log.debug("Lifecycle event publish failed: {}", t.getMessage());
    }
  }

  /**
   * Update sampling pressure based on observed load. When the load factor is high, reduce sampling
   * rate to protect the host application.
   *
   * <p>This is intentionally conservative: it reduces sampling, it does not disable interception.
   *
   * @param loadFactor 0.0-1.0 load indicator from the caller
   */
  public static void updateSamplingPressure(double loadFactor) {
    if (emergencyMode) {
      return;
    }
    lastLoadFactor = loadFactor;
    if (loadFactor > 0.85) {
      SamplingController controller = SamplingController.getInstance();
      int maxRate = controller.getMaxRate();
      int targetRate = Math.max(2, maxRate / 2);
      if (controller.getSamplingRate() < targetRate) {
        controller.setSamplingRate(targetRate);
        log.warn(
            "[GracefulDegradation] High load detected (loadFactor={}), sampling rate increased to {}",
            loadFactor,
            targetRate);
      }
    }
  }

  /**
   * Notify the manager that the span export backend is unreachable or failing. Repeated failures
   * within a short window trigger log-only / local-cache behavior or, under sustained pressure,
   * emergency mode.
   *
   * @param consecutiveFailures number of consecutive export failures
   */
  public static void notifyExportBackendUnavailable(long consecutiveFailures) {
    if (emergencyMode) {
      return;
    }
    long now = System.nanoTime();
    if (consecutiveFailures > 0 && consecutiveFailures % 10 == 0) {
      log.warn(
          "[GracefulDegradation] Export backend unavailable after {} consecutive failures",
          consecutiveFailures);
      updateSamplingPressure(0.9);
      lastExportFailureNanos = now;
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.registry(
                    LifecycleEvents.PHASE_EMERGENCY,
                    "export-backend-unavailable",
                    true,
                    "consecutiveFailures=" + consecutiveFailures));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
    }
    if (consecutiveFailures >= 200) {
      enterEmergencyMode("export-failure:" + consecutiveFailures);
    }
  }

  /** @return most recent load factor observed by {@link #updateSamplingPressure(double)}. */
  public static double getLastLoadFactor() {
    return lastLoadFactor;
  }

  /** Reset state for tests. */
  public static void resetForTest() {
    emergencyMode = false;
    emergencyLogged = false;
    lastLoadFactor = 0.0;
    lastExportFailureNanos = 0L;
  }
}
