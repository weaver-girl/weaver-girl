package com.github.cc11001100.weavergirl.plugins.hbase;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache HBase instrumentation plugin. Times the RPC a client call turns into, which is where a get
 * or a scan spends its time.
 *
 * <p>{@code AbstractRpcClient} is the base every RPC client extends and {@code callMethod} is the
 * method that performs the call, so it is matched by superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCallThreshold} — Slow call threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class HBasePlugin extends AbstractPlugin {

  private long slowCallThresholdMs = 200;
  private boolean enabled = true;

  private static final String RPC_CLIENT = "org.apache.hadoop.hbase.ipc.AbstractRpcClient";

  @Override
  public String name() {
    return "hbase";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.hadoop.hbase.ipc.AbstractRpcClient";
  }

  @Override
  public void init(PluginContext context) {
    slowCallThresholdMs = context.getConfigLong("slowCallThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-hbase", "hbase-error", slowCallThresholdMs);

    registry.register(
        interceptSubclassOf(RPC_CLIENT)
            .method("callMethod")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
