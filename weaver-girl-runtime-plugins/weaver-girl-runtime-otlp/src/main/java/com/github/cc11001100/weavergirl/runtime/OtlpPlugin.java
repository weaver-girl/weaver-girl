package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports interceptor events to an OTLP/HTTP endpoint as log records. Enabled with {@code
 * otlpLogsEndpoint}, the full URL of a collector's {@code /v1/logs} receiver.
 *
 * <p>{@code otlpEndpoint} is still honoured for backwards compatibility, but it is also the trace
 * export endpoint ({@code /v1/traces}), so a {@code /v1/traces} URL is not reused for logs: every
 * collector would reject the batch. Use {@code otlpLogsEndpoint} when both signals are enabled.
 *
 * <p>This registers no interceptors; it only subscribes to the event bus, and only when an endpoint
 * is configured, so shipping the jar costs nothing until then.
 */
public class OtlpPlugin implements WeaverPlugin {

  private static final Logger log = LoggerFactory.getLogger(OtlpPlugin.class);

  static final String NAME = "otlp";

  /** Preferred logs endpoint; keeps log and trace endpoints independent. */
  static final String CONFIG_LOGS_ENDPOINT = "otlpLogsEndpoint";

  /** Shared endpoint historically used for both signals. */
  static final String CONFIG_LEGACY_ENDPOINT = "otlpEndpoint";

  private static final int DEFAULT_TIMEOUT_MS = 1000;

  private OtlpEventListener listener;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return resolveLogsEndpoint(context) != null;
  }

  /**
   * The endpoint log records are posted to, or null when the capability should stay off.
   *
   * <p>{@code otlpLogsEndpoint} always wins. The legacy {@code otlpEndpoint} is accepted only when
   * it does not point at a traces receiver: posting {@code ExportLogsServiceRequest} JSON to
   * {@code /v1/traces} fails at every collector and would burn one warning per event.
   */
  static String resolveLogsEndpoint(PluginContext context) {
    String logs = context.getConfig(CONFIG_LOGS_ENDPOINT);
    if (logs != null && !logs.trim().isEmpty()) {
      return logs.trim();
    }
    String legacy = context.getConfig(CONFIG_LEGACY_ENDPOINT);
    if (legacy == null || legacy.trim().isEmpty()) {
      return null;
    }
    String trimmed = legacy.trim();
    return trimmed.endsWith("/v1/traces") ? null : trimmed;
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With no endpoint there is nowhere to send.
    String endpoint = resolveLogsEndpoint(context);
    if (endpoint == null) {
      return;
    }
    int timeoutMs = context.getConfigInt("otlpTimeoutMs", DEFAULT_TIMEOUT_MS);
    if (timeoutMs <= 0) {
      log.warn("Invalid otlpTimeoutMs value, using {}", DEFAULT_TIMEOUT_MS);
      timeoutMs = DEFAULT_TIMEOUT_MS;
    }
    listener = new OtlpEventListener(endpoint, timeoutMs);
    InterceptorEventPublisher.getInstance().addListener(listener);
    log.info("OTLP log export enabled to {}", endpoint);
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
