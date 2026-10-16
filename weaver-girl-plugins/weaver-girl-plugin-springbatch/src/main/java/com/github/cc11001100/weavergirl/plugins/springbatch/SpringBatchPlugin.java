package com.github.cc11001100.weavergirl.plugins.springbatch;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring Batch instrumentation plugin. Times a chunk being processed and written.
 *
 * <p>SimpleChunkProcessor is the class that runs a chunk's items and writes the result, so doProcess and doWrite are matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowChunkThreshold} — Slow chunk threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SpringBatchPlugin extends AbstractPlugin {

  private long thresholdMs = 1000;
  private boolean enabled = true;

  @Override
  public String name() {
    return "springbatch";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.batch.core.step.item.SimpleChunkProcessor";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowChunkThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-springbatch", "springbatch-error", thresholdMs);

    registry.register(
        intercept("org.springframework.batch.core.step.item.SimpleChunkProcessor")
            .method("doProcess")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
    registry.register(
        intercept("org.springframework.batch.core.step.item.SimpleChunkProcessor")
            .method("doWrite")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

  }
}
