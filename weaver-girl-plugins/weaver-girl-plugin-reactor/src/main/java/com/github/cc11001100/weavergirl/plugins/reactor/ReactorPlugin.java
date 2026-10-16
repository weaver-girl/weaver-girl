package com.github.cc11001100.weavergirl.plugins.reactor;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Project Reactor instrumentation plugin. Times subscription, which is the moment a cold publisher
 * starts its work, and operator assembly, which is where a pipeline is built.
 *
 * <p>{@code Flux} and {@code Mono} are abstract classes every publisher extends, so they are matched
 * by superclass. {@code subscribe} starts the flow and {@code onAssembly} wraps each operator.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowSubscribeThreshold} — Slow subscribe threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ReactorPlugin extends AbstractPlugin {

  private long slowSubscribeThresholdMs = 200;
  private boolean enabled = true;

  private static final String FLUX = "reactor.core.publisher.Flux";
  private static final String MONO = "reactor.core.publisher.Mono";

  @Override
  public String name() {
    return "reactor";
  }

  @Override
  public String instrumentedLibrary() {
    return "reactor.core.publisher.Mono";
  }

  @Override
  public void init(PluginContext context) {
    slowSubscribeThresholdMs = context.getConfigLong("slowSubscribeThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-reactor", "reactor-error", slowSubscribeThresholdMs);

    for (String publisher : new String[] {FLUX, MONO}) {
      registry.register(
          interceptSubclassOf(publisher)
              .methodPattern("subscribe|onAssembly")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
