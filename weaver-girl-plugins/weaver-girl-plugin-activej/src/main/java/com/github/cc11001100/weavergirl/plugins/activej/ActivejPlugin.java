package com.github.cc11001100.weavergirl.plugins.activej;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * ActiveJ HTTP instrumentation plugin. Times a request being served.
 *
 * <p>{@code AsyncServlet} is the interface every handler implements and {@code serve} is the method
 * called with the request, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ActivejPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String SERVLET = "io.activej.http.AsyncServlet";

  @Override
  public String name() {
    return "activej";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.activej.http.AsyncServlet";
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
        new TimingInterceptor(name(), "slow-activej", "activej-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(SERVLET)
            .method("serve")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
