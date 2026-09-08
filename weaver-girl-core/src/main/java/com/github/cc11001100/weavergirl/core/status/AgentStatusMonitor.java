package com.github.cc11001100.weavergirl.core.status;

/**
 * JMX MBean implementation that delegates to AgentStatus. Named without the "MBean" suffix so it
 * can be registered via StandardMBean with an explicit interface, avoiding JMX naming convention
 * conflicts.
 */
public class AgentStatusMonitor implements WeaverGirlMBean {

  private final AgentStatus status;

  public AgentStatusMonitor() {
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
