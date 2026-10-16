package com.github.cc11001100.weavergirl.plugins.jaxrs;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * JAX-RS instrumentation plugin. Times a request filter running.
 *
 * <p>{@code ContainerRequestFilter} is the interface an application implements to intercept a
 * request before the resource method, and {@code filter} is its single method. Both the {@code
 * javax} (JAX-RS 2) and {@code jakarta} (JAX-RS 3) packages are covered, since an application uses
 * one or the other, and both are matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JaxrsPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String[] FILTERS = {
    "javax.ws.rs.container.ContainerRequestFilter",
    "jakarta.ws.rs.container.ContainerRequestFilter"
  };

  @Override
  public String name() {
    return "jaxrs";
  }

  @Override
  public String instrumentedLibrary() {
    return "javax.ws.rs.container.ContainerRequestFilter";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-jaxrs", "jaxrs-error", slowRequestThresholdMs);

    for (String filter : FILTERS) {
      registry.register(
          interceptImplementing(filter)
              .method("filter")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
