package com.github.cc11001100.weavergirl.core.management;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

/**
 * Self-monitoring helper for agent runtime health.
 *
 * <p>Reads lightweight JVM metrics on demand; callers should cache results rather than polling
 * rapidly.
 */
final class AgentSelfMonitor {

  private static final OperatingSystemMXBean OS_MX_BEAN;
  private static final ThreadMXBean THREAD_MX_BEAN;

  static {
    OperatingSystemMXBean osBean;
    try {
      osBean = ManagementFactory.getOperatingSystemMXBean();
    } catch (Exception e) {
      osBean = null;
    }
    OS_MX_BEAN = osBean;

    ThreadMXBean threadBean;
    try {
      threadBean = ManagementFactory.getThreadMXBean();
    } catch (Exception e) {
      threadBean = null;
    }
    THREAD_MX_BEAN = threadBean;
  }

  private AgentSelfMonitor() {}

  static double getCpuUsage() {
    if (OS_MX_BEAN instanceof com.sun.management.OperatingSystemMXBean) {
      try {
        return ((com.sun.management.OperatingSystemMXBean) OS_MX_BEAN).getSystemCpuLoad();
      } catch (Exception e) {
        return -1;
      }
    }
    return -1;
  }

  static long getThreadCount() {
    if (THREAD_MX_BEAN != null) {
      try {
        return THREAD_MX_BEAN.getThreadCount();
      } catch (Exception e) {
        return -1;
      }
    }
    return -1;
  }

  static long getDaemonThreadCount() {
    if (THREAD_MX_BEAN != null) {
      try {
        return THREAD_MX_BEAN.getDaemonThreadCount();
      } catch (Exception e) {
        return -1;
      }
    }
    return -1;
  }

  static long getHeapMemoryUsed() {
    try {
      return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    } catch (Exception e) {
      return -1;
    }
  }

  static long getNonHeapMemoryUsed() {
    try {
      return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
    } catch (Exception e) {
      return -1;
    }
  }
}
