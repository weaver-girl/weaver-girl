package com.github.cc11001100.weavergirl.core.management;

/**
 * JMX MBean interface for monitoring the weaver-girl agent at runtime.
 *
 * <p>Register via: {@code AgentMBean.register()}</p>
 *
 * <p>Access via JConsole / VisualVM / JMC:</p>
 * <pre>
 * MBean name: com.github.cc11001100.weavergirl:type=Agent
 * </pre>
 *
 * <p>Or programmatically:</p>
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

    /** Reset all counters to zero. */
    void resetCounters();
}
