package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentMonitorTest {

  private AgentMonitor monitor;

  @BeforeEach
  void setUp() {
    monitor = AgentMonitor.getInstance();
    monitor.resetCounters();
    monitor.unregister();
  }

  @AfterEach
  void tearDown() {
    monitor.unregister();
  }

  @Test
  void incrementInterceptCount_accumulates() {
    assertEquals(0, monitor.getTotalInterceptCount());
    monitor.incrementInterceptCount();
    assertEquals(1, monitor.getTotalInterceptCount());
    monitor.incrementInterceptCount();
    monitor.incrementInterceptCount();
    assertEquals(3, monitor.getTotalInterceptCount());
  }

  @Test
  void addInterceptTime_tracksTotalTime() {
    assertEquals(0, monitor.getTotalInterceptTimeMs());
    monitor.addInterceptTime(1_000_000); // 1ms
    assertEquals(1, monitor.getTotalInterceptTimeMs());
    monitor.addInterceptTime(2_000_000); // 2ms
    assertEquals(3, monitor.getTotalInterceptTimeMs());
  }

  @Test
  void averageInterceptTimeUs_calculatesCorrectly() {
    monitor.incrementInterceptCount();
    monitor.incrementInterceptCount();
    monitor.addInterceptTime(2_000_000); // 2ms total = 2000us
    assertEquals(1000.0, monitor.getAverageInterceptTimeUs(), 0.1);
  }

  @Test
  void averageInterceptTimeUs_zeroCount_returnsZero() {
    assertEquals(0.0, monitor.getAverageInterceptTimeUs());
  }

  @Test
  void transformedClassCount_tracksCorrectly() {
    assertEquals(0, monitor.getTransformedClassCount());
    monitor.incrementTransformedClassCount();
    assertEquals(1, monitor.getTransformedClassCount());
  }

  @Test
  void pluginMetadata_setAndGet() {
    monitor.setPluginCount(12);
    monitor.setPluginNames("jdbc,servlet,redis");
    monitor.setAgentVersion("1.2.3");
    monitor.setInterceptorDefinitionCount(30);

    assertEquals(12, monitor.getPluginCount());
    assertEquals("jdbc,servlet,redis", monitor.getPluginNames());
    assertEquals("1.2.3", monitor.getAgentVersion());
    assertEquals(30, monitor.getInterceptorDefinitionCount());
  }

  @Test
  void excludedClassCount_tracksCorrectly() {
    monitor.setExcludedClassCount(42);
    assertEquals(42, monitor.getExcludedClassCount());
  }

  @Test
  void resetCounters_clearsAllCounters() {
    monitor.incrementInterceptCount();
    monitor.addInterceptTime(5_000_000);
    monitor.incrementTransformedClassCount();
    monitor.resetCounters();

    assertEquals(0, monitor.getTotalInterceptCount());
    assertEquals(0, monitor.getTotalInterceptTimeMs());
    assertEquals(0, monitor.getTransformedClassCount());
  }

  @Test
  void register_jmxMBeanAccessible() throws Exception {
    monitor.register();

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.github.cc11001100.weavergirl:type=Agent");
    assertTrue(server.isRegistered(name));

    // Verify we can read attributes
    Long count = (Long) server.getAttribute(name, "TotalInterceptCount");
    assertEquals(0L, count.longValue());
  }

  @Test
  void register_idempotent() {
    assertDoesNotThrow(
        () -> {
          monitor.register();
          monitor.register(); // second call is no-op
        });
  }

  @Test
  void unregister_removesMBean() throws Exception {
    monitor.register();
    monitor.unregister();

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.github.cc11001100.weavergirl:type=Agent");
    assertFalse(server.isRegistered(name));
  }

  @Test
  void setNullValues_handledGracefully() {
    assertDoesNotThrow(
        () -> {
          monitor.setAgentVersion(null);
          monitor.setPluginNames(null);
        });
    assertEquals("unknown", monitor.getAgentVersion());
    assertEquals("", monitor.getPluginNames());
  }

  /**
   * A startup that was never finished has no duration to report, so the gauge answers with the sentinel
   * rather than a time.
   */
  @Test
  void aStartupThatNeverFinishedReportsNoDuration() throws Exception {
    java.lang.reflect.Field start = StartupMetrics.class.getDeclaredField("startNanos");
    java.lang.reflect.Field end = StartupMetrics.class.getDeclaredField("endNanos");
    Object savedStart = com.github.cc11001100.weavergirl.core.testsupport.Statics.read(start, null);
    Object savedEnd = com.github.cc11001100.weavergirl.core.testsupport.Statics.read(end, null);
    try {
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(start, null, Long.valueOf(0L));
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(end, null, Long.valueOf(0L));
      assertEquals(-1L, monitor.getStartupMillis(), "an unfinished startup reported a duration");
    } finally {
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(start, null, savedStart);
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(end, null, savedEnd);
    }
  }

  /** The runtime gauges answer through the monitor, and a completed startup reports a duration. */
  @Test
  void theRuntimeGaugesAnswer() {
    StartupMetrics.begin();
    StartupMetrics.end();

    assertTrue(monitor.getStartupMillis() >= 0, "a completed startup reported no duration");
    assertTrue(monitor.getCpuUsage() >= 0.0);
    assertTrue(monitor.getThreadCount() > 0);
    assertTrue(monitor.getDaemonThreadCount() >= 0);
    assertTrue(monitor.getHeapMemoryUsed() > 0);
    assertTrue(monitor.getNonHeapMemoryUsed() >= 0);
  }

  /**
   * The MBean server reports the name as free and then rejects the registration. Both registering and
   * unregistering log the failure and leave the monitor usable.
   */
  @Test
  void aServerThatRejectsTheRegistrationIsSurvived() throws Exception {
    java.lang.reflect.Field server =
        java.lang.management.ManagementFactory.class.getDeclaredField("platformMBeanServer");
    Object real = com.github.cc11001100.weavergirl.core.testsupport.Statics.read(server, null);
    try {
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(
          server, null, rejectingServer(false));
      assertDoesNotThrow(monitor::register);

      // The name reports as registered, so unregistering reaches the call that throws.
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(
          server, null, rejectingServer(true));
      java.lang.reflect.Field registered = AgentMonitor.class.getDeclaredField("registered");
      registered.setAccessible(true);
      registered.set(monitor, true);
      assertDoesNotThrow(monitor::unregister);
      registered.set(monitor, false);
    } finally {
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(server, null, real);
    }
  }

  /**
   * An MBean server that says nothing is registered and then fails every registration and removal.
   * Everything else is answered by a real, empty server.
   */
  private static javax.management.MBeanServer rejectingServer(final boolean reportsRegistered) {
    final javax.management.MBeanServer delegate = javax.management.MBeanServerFactory.newMBeanServer();
    return (javax.management.MBeanServer)
        java.lang.reflect.Proxy.newProxyInstance(
            javax.management.MBeanServer.class.getClassLoader(),
            new Class<?>[] {javax.management.MBeanServer.class},
            new java.lang.reflect.InvocationHandler() {
              public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                  throws Throwable {
                String called = method.getName();
                if ("isRegistered".equals(called)) {
                  return Boolean.valueOf(reportsRegistered);
                }
                if ("registerMBean".equals(called)) {
                  throw new javax.management.InstanceAlreadyExistsException(String.valueOf(args[1]));
                }
                if ("unregisterMBean".equals(called)) {
                  throw new javax.management.InstanceNotFoundException(String.valueOf(args[0]));
                }
                return method.invoke(delegate, args);
              }
            });
  }


  /** Unregistering a monitor that was never registered changes nothing. */
  @Test
  void unregisteringOneThatWasNeverRegisteredDoesNothing() {
    monitor.unregister();
    assertDoesNotThrow(monitor::unregister);
  }
}
