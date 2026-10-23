package com.github.cc11001100.weavergirl.api.observability;

import java.util.Map;

/**
 * Contributes OTLP resource attributes for spans exported by the agent.
 *
 * <p>Resource attributes describe the process that produced a span, not the operation: {@code
 * service.name}, {@code service.version}, {@code deployment.environment} and, in a cloud
 * deployment, {@code cloud.region}, {@code k8s.pod.name}, {@code host.id} and similar. The agent
 * fills in the first group from its launch configuration. A plugin that knows more — an
 * infrastructure plugin that can read the pod name, a product that stamps a tenant onto every span —
 * implements this interface and registers it through {@link
 * com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerResourceAttributes} (or {@link
 * ResourceAttributes#register}).
 *
 * <p>Providers are read when a span batch is exported, so a provider registered after the exporter
 * started still applies. When several providers return the same key the last registered one wins,
 * and an explicitly configured key ({@code serviceName}, {@code serviceVersion}, {@code
 * deploymentEnvironment}) always wins over a provider.
 *
 * <p>Prefer OpenTelemetry semantic-convention names; the collector and most backends understand
 * them without extra mapping.
 *
 * @since 1.0.0
 */
public interface ResourceAttributeProvider {

  /**
   * The resource attributes this provider currently contributes.
   *
   * <p>Called on the exporter thread once per batch. The map may be empty; null keys and values are
   * ignored.
   *
   * @return attribute names to values
   */
  Map<String, String> resourceAttributes();
}
