package com.github.cc11001100.weavergirl.plugins.webflux;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring WebFlux instrumentation plugin. Times a reactive request being dispatched.
 *
 * <p>DispatcherHandler.handle is the single method every request passes through on its way to a handler, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class WebFluxPlugin extends AbstractPlugin {

  private long thresholdMs = 1000;
  private boolean enabled = true;

  @Override
  public String name() {
    return "webflux";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.web.reactive.DispatcherHandler";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowRequestThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-webflux", "webflux-error", thresholdMs);

    registry.register(
        intercept("org.springframework.web.reactive.DispatcherHandler")
            .method("handle")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
