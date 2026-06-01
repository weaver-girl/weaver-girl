package com.github.cc11001100.weavergirl.core.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

class JmxRegistrarTest {

    private static final String OBJECT_NAME = "com.github.cc11001100.weavergirl:type=Agent";

    @AfterEach
    void cleanup() {
        // Ensure MBean is unregistered after each test
        JmxRegistrar.unregister();
    }

    @Test
    void registerDoesNotThrow() {
        assertDoesNotThrow(() -> JmxRegistrar.register());
    }

    @Test
    void afterRegisterMBeanIsRegisteredWithCorrectObjectName() throws Exception {
        JmxRegistrar.register();

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(OBJECT_NAME);
        assertTrue(server.isRegistered(name));
    }

    @Test
    void afterUnregisterMBeanIsNoLongerRegistered() throws Exception {
        JmxRegistrar.register();
        JmxRegistrar.unregister();

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(OBJECT_NAME);
        assertFalse(server.isRegistered(name));
    }

    @Test
    void doubleRegisterIsSafe() {
        assertDoesNotThrow(() -> {
            JmxRegistrar.register();
            JmxRegistrar.register();
        });
    }

    @Test
    void getStatusReportReturnsNonNullViaJmx() throws Exception {
        JmxRegistrar.register();

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(OBJECT_NAME);
        Object report = server.getAttribute(name, "StatusReport");
        assertNotNull(report);
        assertInstanceOf(String.class, report);
        assertFalse(((String) report).isEmpty());
    }
}
