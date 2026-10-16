package com.github.cc11001100.weavergirl.plugins.kotlin;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Kotlin coroutines instrumentation plugin. Times coroutine dispatch and flow collection, which is
 * where a suspended computation actually runs.
 *
 * <p>{@code CoroutineDispatcher} is the abstract base every dispatcher extends and {@code dispatch}
 * is the method that hands a coroutine to a thread, so it is matched by superclass. {@code Flow} is
 * the interface every flow implements and {@code collect} is the method that starts it, so it is
 * matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowDispatchThreshold} — Slow dispatch threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class KotlinPlugin extends AbstractPlugin {

  private long slowDispatchThresholdMs = 200;
  private boolean enabled = true;

  private static final String DISPATCHER = "kotlinx.coroutines.CoroutineDispatcher";
  private static final String FLOW = "kotlinx.coroutines.flow.Flow";

  @Override
  public String name() {
    return "kotlin";
  }

  @Override
  public String instrumentedLibrary() {
    return "kotlinx.coroutines.CoroutineDispatcher";
  }

  @Override
  public void init(PluginContext context) {
    slowDispatchThresholdMs = context.getConfigLong("slowDispatchThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-kotlin", "kotlin-error", slowDispatchThresholdMs);

    registry.register(
        interceptSubclassOf(DISPATCHER)
            .method("dispatch")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(FLOW)
            .method("collect")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
