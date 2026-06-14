package com.github.cc11001100.weavergirl.core.switches;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide kill-switch for all interception. When disabled, {@code InterceptAdvice}
 * returns immediately on every enter/exit with a single volatile read, so the host
 * application incurs no interceptor dispatch cost.
 *
 * <p>Read on the hottest path of the agent, so the flag is a plain {@code volatile}
 * (no locks, no allocation). Mutations are auditable: every transition increments a
 * counter and is logged with the source (config init, REST toggle, JMX).</p>
 *
 * @since 1.0.0
 */
public final class GlobalInterceptionSwitch {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalInterceptionSwitch.class);

  private static volatile boolean enabled = true;
  private static final AtomicLong toggleCount = new AtomicLong();

  private GlobalInterceptionSwitch() {}

  /** @return true if interception is active (default). Cheap volatile read. */
  public static boolean isEnabled() {
    return enabled;
  }

  /**
   * Enable or disable interception process-wide.
   *
   * @param newValue the desired state
   * @param source who/what triggered the change (e.g. "config", "rest:/agent/interception", "jmx")
   */
  public static void setEnabled(boolean newValue, String source) {
    if (newValue != enabled) {
      enabled = newValue;
      toggleCount.incrementAndGet();
      LOG.warn("Global interception {} by source={}", newValue ? "ENABLED" : "DISABLED", source);
    }
  }

  /** @return how many times the switch has toggled since JVM start. */
  public static long toggleCount() {
    return toggleCount.get();
  }

  /** Test-only reset. */
  static void resetForTest() {
    enabled = true;
    toggleCount.set(0);
  }
}
