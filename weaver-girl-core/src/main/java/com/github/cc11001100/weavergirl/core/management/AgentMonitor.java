package com.github.cc11001100.weavergirl.core.management;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of AgentMXBean that tracks agent runtime metrics.
 *
 * <p>Thread-safe. All counters use AtomicLong/AtomicInteger.
 *
 * <p>Usage:
 *
 * <pre>
 * AgentMonitor monitor = AgentMonitor.getInstance();
 * monitor.register(); // register JMX MBean
 * monitor.incrementInterceptCount();
 * monitor.addInterceptTime(elapsedNanos);
 * </pre>
 */
public class AgentMonitor implements AgentMXBean {

  private static final Logger log = LoggerFactory.getLogger(AgentMonitor.class);
  private static final AgentMonitor INSTANCE = new AgentMonitor();

  private static final String MBEAN_NAME = "com.github.cc11001100.weavergirl:type=Agent";

  private final AtomicLong interceptCount = new AtomicLong(0);
  private final AtomicLong interceptTimeNanos = new AtomicLong(0);
  private final AtomicInteger transformedClassCount = new AtomicInteger(0);
  private final AtomicInteger interceptorDefinitionCount = new AtomicInteger(0);
  private final AtomicInteger pluginCount = new AtomicInteger(0);
  private final AtomicInteger excludedClassCount = new AtomicInteger(0);

  private volatile String agentVersion = "1.0.0-SNAPSHOT";
  private volatile String pluginNames = "";

  private volatile boolean registered = false;

  private AgentMonitor() {}

  public static AgentMonitor getInstance() {
    return INSTANCE;
  }

  /**
   * Register this monitor as a JMX MBean. Safe to call multiple times — no-op if already
   * registered.
   */
  public void register() {
    if (registered) return;
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      ObjectName name = new ObjectName(MBEAN_NAME);
      if (!server.isRegistered(name)) {
        server.registerMBean(this, name);
        registered = true;
        log.debug("Agent JMX MBean registered: {}", MBEAN_NAME);
      }
    } catch (Exception e) {
      log.warn("Failed to register JMX MBean: {}", e.getMessage());
    }
  }

  /** Unregister the JMX MBean. */
  public void unregister() {
    if (!registered) return;
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      ObjectName name = new ObjectName(MBEAN_NAME);
      if (server.isRegistered(name)) {
        server.unregisterMBean(name);
      }
      registered = false;
    } catch (Exception e) {
      log.warn("Failed to unregister JMX MBean: {}", e.getMessage());
    }
  }

  // --- Counter update methods ---

  public void incrementInterceptCount() {
    interceptCount.incrementAndGet();
  }

  public void addInterceptTime(long nanos) {
    interceptTimeNanos.addAndGet(nanos);
  }

  public void incrementTransformedClassCount() {
    transformedClassCount.incrementAndGet();
  }

  public void setInterceptorDefinitionCount(int count) {
    interceptorDefinitionCount.set(count);
  }

  public void setPluginCount(int count) {
    pluginCount.set(count);
  }

  public void setPluginNames(String names) {
    this.pluginNames = names != null ? names : "";
  }

  public void setAgentVersion(String version) {
    this.agentVersion = version != null ? version : "unknown";
  }

  public void setExcludedClassCount(int count) {
    excludedClassCount.set(count);
  }

  // --- MXBean interface implementation ---

  @Override
  public long getTotalInterceptCount() {
    return interceptCount.get();
  }

  @Override
  public long getTotalInterceptTimeMs() {
    return interceptTimeNanos.get() / 1_000_000;
  }

  @Override
  public double getAverageInterceptTimeUs() {
    long count = interceptCount.get();
    if (count == 0) return 0.0;
    return (interceptTimeNanos.get() / 1_000.0) / count;
  }

  @Override
  public int getTransformedClassCount() {
    return transformedClassCount.get();
  }

  @Override
  public int getInterceptorDefinitionCount() {
    return interceptorDefinitionCount.get();
  }

  @Override
  public int getPluginCount() {
    return pluginCount.get();
  }

  @Override
  public String getAgentVersion() {
    return agentVersion;
  }

  @Override
  public String getPluginNames() {
    return pluginNames;
  }

  @Override
  public int getExcludedClassCount() {
    return excludedClassCount.get();
  }

  @Override
  public long getStartupMillis() {
    return StartupMetrics.totalMillis();
  }

  @Override
  public void resetCounters() {
    interceptCount.set(0);
    interceptTimeNanos.set(0);
    transformedClassCount.set(0);
  }
}
