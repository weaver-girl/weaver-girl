package com.github.cc11001100.weavergirl.plugins.tomcat;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Tomcat instrumentation plugin. Times request handling at the connector adapter, the point
 * every request passes through before it reaches a servlet.
 *
 * <p>{@code CoyoteAdapter} is a concrete class and {@code service} is the method the connector calls
 * for each request, so both are matched by name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class TomcatPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String COYOTE_ADAPTER = "org.apache.catalina.connector.CoyoteAdapter";

  @Override
  public String name() {
    return "tomcat";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.catalina.connector.CoyoteAdapter";
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
        new TimingInterceptor(name(), "slow-tomcat", "tomcat-error", slowRequestThresholdMs);

    registry.register(
        intercept(COYOTE_ADAPTER)
            .method("service")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
