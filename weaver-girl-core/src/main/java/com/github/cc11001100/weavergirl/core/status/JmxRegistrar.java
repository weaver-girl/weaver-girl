package com.github.cc11001100.weavergirl.core.status;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the weaver-girl agent MBean with the platform MBean server. */
public class JmxRegistrar {

  private static final Logger log = LoggerFactory.getLogger(JmxRegistrar.class);
  private static final String OBJECT_NAME = "com.github.cc11001100.weavergirl:type=Agent";

  /**
   * Register the agent MBean. Safe to call multiple times — duplicate registration is silently
   * ignored.
   */
  public static void register() {
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      ObjectName name = new ObjectName(OBJECT_NAME);
      if (!server.isRegistered(name)) {
        StandardMBean mbean = new StandardMBean(new AgentStatusMonitor(), WeaverGirlMBean.class);
        server.registerMBean(mbean, name);
        log.info("Registered JMX MBean: {}", OBJECT_NAME);
      }
    } catch (Exception e) {
      log.warn("Failed to register JMX MBean: {}", e.getMessage());
    }
  }

  /** Unregister the agent MBean. */
  public static void unregister() {
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      ObjectName name = new ObjectName(OBJECT_NAME);
      if (server.isRegistered(name)) {
        server.unregisterMBean(name);
        log.info("Unregistered JMX MBean: {}", OBJECT_NAME);
      }
    } catch (Exception e) {
      log.warn("Failed to unregister JMX MBean: {}", e.getMessage());
    }
  }
}
