package com.github.cc11001100.weavergirl.api.alert;

/**
 * Receives alert notifications.
 *
 * @since 1.2.0
 */
@FunctionalInterface
public interface AlertChannel {

  /**
   * Handle an alert event.
   *
   * @param alert the triggered alert
   */
  void onAlert(AlertEvent alert);
}
