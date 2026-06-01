package com.github.cc11001100.weavergirl.core.status;

/**
 * JMX MXBean implementation that delegates to AgentStatus.
 * Register with: MBeanServerFactory.findMBeanServer(null).get(0)
 *   .registerMBean(new WeaverGirlMXBean(), new ObjectName("com.github.cc11001100.weavergirl:type=Agent"));
 */
public class WeaverGirlMXBean implements WeaverGirlMBean {

    private final AgentStatus status;

    public WeaverGirlMXBean() {
        this.status = AgentStatus.getInstance();
    }

    @Override
    public long getTransformationCount() {
        return status.getTransformationCount();
    }

    @Override
    public long getTransformationErrorCount() {
        return status.getTransformationErrorCount();
    }

    @Override
    public long getInterceptorInvocationCount() {
        return status.getInterceptorInvocationCount();
    }

    @Override
    public long getInterceptorErrorCount() {
        return status.getInterceptorErrorCount();
    }

    @Override
    public int getActivePluginCount() {
        return status.getActivePluginCount();
    }

    @Override
    public int getRegisteredInterceptorCount() {
        return status.getRegisteredInterceptorCount();
    }

    @Override
    public long getUptimeSeconds() {
        return status.getUptimeSeconds();
    }

    @Override
    public String getStatusReport() {
        return status.getReport();
    }
}
