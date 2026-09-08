package com.github.cc11001100.weavergirl.core.management;

/**
 * JMX MBean interface for monitoring the weaver-girl agent at runtime.
 *
 * <p>Register via: {@code AgentMBean.register()}
 *
 * <p>Access via JConsole / VisualVM / JMC:
 *
 * <pre>
 * MBean name: com.github.cc11001100.weavergirl:type=Agent
 * </pre>
 *
 * <p>Or programmatically:
 *
 * <pre>
 * ObjectName name = new ObjectName("com.github.cc11001100.weavergirl:type=Agent");
 * long interceptCount = (Long) mBeanServer.getAttribute(name, "TotalInterceptCount");
 * </pre>
 */
public interface AgentMXBean {

  /** Total number of intercepted method calls since agent started. */
  long getTotalInterceptCount();

  /** Total time spent in interceptors (before + after) in milliseconds. */
  long getTotalInterceptTimeMs();

  /** Average intercept overhead per call in microseconds. */
  double getAverageInterceptTimeUs();

  /** Number of classes that were bytecode-transformed. */
  int getTransformedClassCount();

  /** Number of active interceptor definitions. */
  int getInterceptorDefinitionCount();

  /** Number of loaded plugins. */
  int getPluginCount();

  /** Agent version string. */
  String getAgentVersion();

  /** Names of all loaded plugins, comma-separated. */
  String getPluginNames();

  /** Number of classes excluded from transformation. */
  int getExcludedClassCount();

  /** Agent bootstrap time in ms (-1 if not measured). */
  long getStartupMillis();

  /** System CPU usage, 0.0-1.0, or -1 if unavailable. */
  double getCpuUsage();

  /** Current JVM thread count, or -1 if unavailable. */
  long getThreadCount();

  /** Current JVM daemon thread count, or -1 if unavailable. */
  long getDaemonThreadCount();

  /** Heap memory used in bytes, or -1 if unavailable. */
  long getHeapMemoryUsed();

  /** Non-heap memory used in bytes, or -1 if unavailable. */
  long getNonHeapMemoryUsed();

  /** Reset all counters to zero. */
  void resetCounters();
}
