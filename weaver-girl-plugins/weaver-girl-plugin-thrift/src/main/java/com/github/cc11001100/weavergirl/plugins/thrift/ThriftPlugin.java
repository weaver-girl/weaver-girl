package com.github.cc11001100.weavergirl.plugins.thrift;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Thrift instrumentation plugin. Times RPC calls on the client and request processing on the
 * server.
 *
 * <p>Generated clients extend {@code TServiceClient} and generated processors extend {@code
 * TBaseProcessor}, so both are matched by superclass. {@code sendBase} is the client send and {@code
 * process} is the server entry.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCallThreshold} — Slow call threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ThriftPlugin extends AbstractPlugin {

  private long slowCallThresholdMs = 500;
  private boolean enabled = true;

  private static final String SERVICE_CLIENT = "org.apache.thrift.TServiceClient";
  private static final String PROCESSOR = "org.apache.thrift.TBaseProcessor";

  @Override
  public String name() {
    return "thrift";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.thrift.TServiceClient";
  }

  @Override
  public void init(PluginContext context) {
    slowCallThresholdMs = context.getConfigLong("slowCallThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-thrift", "thrift-error", slowCallThresholdMs);

    registry.register(
        interceptSubclassOf(SERVICE_CLIENT)
            .method("sendBase")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        interceptSubclassOf(PROCESSOR)
            .method("process")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
