package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports interceptor events to an OTLP/HTTP endpoint. Enabled with {@code otlpEndpoint}, the full
 * URL of a collector's {@code /v1/logs} receiver.
 *
 * <p>This registers no interceptors; it only subscribes to the event bus, and only when an endpoint
 * is configured, so shipping the jar costs nothing until then.
 */
public class OtlpPlugin implements WeaverPlugin {

  private static final Logger log = LoggerFactory.getLogger(OtlpPlugin.class);

  static final String NAME = "otlp";

  private static final int DEFAULT_TIMEOUT_MS = 1000;

  private OtlpEventListener listener;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    String endpoint = context.getConfig("otlpEndpoint");
    return endpoint != null && !endpoint.trim().isEmpty();
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With no endpoint there is nowhere to send.
    if (!isEnabled(context)) {
      return;
    }
    int timeoutMs = context.getConfigInt("otlpTimeoutMs", DEFAULT_TIMEOUT_MS);
    if (timeoutMs <= 0) {
      log.warn("Invalid otlpTimeoutMs value, using {}", DEFAULT_TIMEOUT_MS);
      timeoutMs = DEFAULT_TIMEOUT_MS;
    }
    listener = new OtlpEventListener(context.getConfig("otlpEndpoint").trim(), timeoutMs);
    InterceptorEventPublisher.getInstance().addListener(listener);
    log.info("OTLP log export enabled to {}", context.getConfig("otlpEndpoint").trim());
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // event subscriber only
  }

  @Override
  public void destroy() {
    if (listener != null) {
      InterceptorEventPublisher.getInstance().removeListener(listener);
      listener.shutdown();
    }
  }
}
