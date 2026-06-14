package com.github.cc11001100.weavergirl.core.status;

/**
 * JMX MBean interface for monitoring the weaver-girl agent.
 */
public interface WeaverGirlMBean {

    long getTransformationCount();
    long getTransformationErrorCount();
    long getInterceptorInvocationCount();
    long getInterceptorErrorCount();
    int getActivePluginCount();
    int getRegisteredInterceptorCount();
    long getUptimeSeconds();
    String getStatusReport();
}
