package com.github.cc11001100.weavergirl.core.alert;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alert channel that logs alerts via SLF4J at WARN level.
 *
 * @since 1.2.0
 */
public class LoggingAlertChannel implements AlertChannel {

  private static final Logger log = LoggerFactory.getLogger(LoggingAlertChannel.class);

  @Override
  public void onAlert(AlertEvent alert) {
    if (alert == null) {
      return;
    }
    log.warn(
        "[ALERT] rule={} severity={} value={} threshold={} time={}",
        alert.getRuleName(),
        alert.getSeverity(),
        alert.getActualValue(),
        alert.getThreshold(),
        alert.getTimestamp());
  }
}
