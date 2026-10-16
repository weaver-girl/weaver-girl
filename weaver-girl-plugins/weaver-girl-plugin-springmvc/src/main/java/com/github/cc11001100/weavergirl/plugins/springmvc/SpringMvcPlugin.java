package com.github.cc11001100.weavergirl.plugins.springmvc;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring MVC instrumentation plugin. Times a controller invocation.
 *
 * <p>{@code HandlerAdapter.handle} is where the dispatcher runs the controller for one request, so
 * every adapter is matched by the interface it implements.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SpringMvcPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "springmvc";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.web.servlet.HandlerAdapter";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-springmvc", "springmvc-error", thresholdMs);

    registry.register(
        interceptImplementing("org.springframework.web.servlet.HandlerAdapter")
            .methodPattern("handle.*")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
