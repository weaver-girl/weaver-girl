package com.github.cc11001100.weavergirl.plugins.springws;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring Web Services instrumentation plugin. Times an endpoint method handling a SOAP message.
 *
 * <p>An endpoint method is the one annotated with PayloadRoot, SoapAction or Action, so it is matched by method annotation.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SpringWsPlugin extends AbstractPlugin {

  private long thresholdMs = 500;
  private boolean enabled = true;

  @Override
  public String name() {
    return "springws";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.ws.server.endpoint.annotation.PayloadRoot";
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
        new TimingInterceptor(name(), "slow-springws", "springws-error", thresholdMs);

    for (String annotation :
        new String[] {
          "org.springframework.ws.server.endpoint.annotation.PayloadRoot",
          "org.springframework.ws.soap.server.endpoint.annotation.SoapAction",
          "org.springframework.ws.soap.addressing.server.annotation.Action"
        }) {
      registry.register(
          interceptClassPattern(".*")
              .methodAnnotated(annotation)
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
