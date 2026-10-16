package com.github.cc11001100.weavergirl.plugins.elasticjob;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache ShardingSphere ElasticJob instrumentation plugin. Times a job running on a shard.
 *
 * <p>Each job type has its own executor and {@code process} is the method that runs the shard, so
 * the four executors are matched by exact name. Simple, dataflow, script, and http jobs are all
 * covered.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowJobThreshold} — Slow job threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ElasticJobPlugin extends AbstractPlugin {

  private long slowJobThresholdMs = 1000;
  private boolean enabled = true;

  private static final String[] EXECUTORS = {
    "org.apache.shardingsphere.elasticjob.simple.executor.SimpleJobExecutor",
    "org.apache.shardingsphere.elasticjob.dataflow.executor.DataflowJobExecutor",
    "org.apache.shardingsphere.elasticjob.script.executor.ScriptJobExecutor",
    "org.apache.shardingsphere.elasticjob.http.executor.HttpJobExecutor"
  };

  @Override
  public String name() {
    return "elasticjob";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.shardingsphere.elasticjob.simple.executor.SimpleJobExecutor";
  }

  @Override
  public void init(PluginContext context) {
    slowJobThresholdMs = context.getConfigLong("slowJobThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-elasticjob", "elasticjob-error", slowJobThresholdMs);

    for (String executor : EXECUTORS) {
      registry.register(
          intercept(executor)
              .method("process")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
