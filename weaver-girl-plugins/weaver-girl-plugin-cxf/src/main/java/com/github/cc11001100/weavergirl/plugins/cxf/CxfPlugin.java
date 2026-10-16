package com.github.cc11001100.weavergirl.plugins.cxf;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache CXF instrumentation plugin. Times a JAX-RS resource method being invoked.
 *
 * <p>{@code JAXRSInvoker} is the class that calls a resource method and {@code invoke} is the
 * method that does it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class CxfPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String INVOKER = "org.apache.cxf.jaxrs.JAXRSInvoker";

  @Override
  public String name() {
    return "cxf";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.cxf.jaxrs.JAXRSInvoker";
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
        new TimingInterceptor(name(), "slow-cxf", "cxf-error", slowRequestThresholdMs);

    registry.register(
        intercept(INVOKER)
            .method("invoke")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
